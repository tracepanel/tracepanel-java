package dev.tracepanel.micronaut;

import dev.tracepanel.core.Console;
import dev.tracepanel.core.Profiler;
import dev.tracepanel.core.RequestDetails;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import dev.tracepanel.core.W3c;
import dev.tracepanel.core.Widget;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.async.propagation.ReactivePropagation;
import io.micronaut.core.propagation.PropagatedContext;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.ServerHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.HttpServerFilter;
import io.micronaut.http.filter.ServerFilterChain;
import io.micronaut.http.filter.ServerFilterPhase;
import io.micronaut.http.server.util.HttpHostResolver;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteAttributes;
import io.micronaut.web.router.UriRouteMatch;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

@Filter("/**")
@Requires(classes = HttpServerFilter.class, condition = TracepanelEnabled.class)
final class TracepanelServerFilter implements HttpServerFilter {
    private final Tracepanel tracepanel;
    private final String prefix;
    private final HttpHostResolver hosts;

    TracepanelServerFilter(Tracepanel tracepanel, @Value("${micronaut.server.context-path:}") String contextPath, HttpHostResolver hosts) {
        this.tracepanel = tracepanel;
        this.hosts = hosts;
        this.prefix = contextPath.replaceAll("/+$", "") + tracepanel.config().prefix();
    }

    @Override
    public int getOrder() {
        return ServerFilterPhase.FIRST.order();
    }

    @Override
    public Publisher<MutableHttpResponse<?>> doFilter(HttpRequest<?> request, ServerFilterChain chain) {
        String path = request.getPath();
        if (path.equals(prefix.substring(0, prefix.length() - 1)) || path.startsWith(prefix)) {
            return console(request, path.length() < prefix.length() ? "" : path.substring(prefix.length()));
        }
        if (request.getMethodName().equals("OPTIONS") && request.getHeaders().contains(HttpHeaders.ORIGIN)
                && request.getHeaders().contains(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD)) {
            return chain.proceed(request);
        }
        return Flux.defer(() -> traced(request, chain));
    }

    @SuppressWarnings("try")
    private Publisher<MutableHttpResponse<?>> traced(HttpRequest<?> request, ServerFilterChain chain) {
        Map<String, Object> attrs = new LinkedHashMap<>(W3c.incoming(request.getHeaders().get(W3c.HEADER)));
        attrs.put("http.method", request.getMethodName());
        attrs.put("http.path", request.getPath());
        if (tracepanel.config().captureRequest()) {
            attrs.putAll(RequestDetails.query(request.getUri().getRawQuery()));
            attrs.putAll(RequestDetails.cookies(request.getHeaders().get(HttpHeaders.COOKIE)));
            attrs.putAll(RequestDetails.requestHeaders(headers(request.getHeaders())));
        }
        Trace previous = Tracepanel.current();
        Trace trace = tracepanel.begin("http", request.getMethodName() + " " + request.getPath(), attrs);
        String profile = request.getHeaders().get(Profiler.HEADER);
        String cookies = request.getHeaders().get(HttpHeaders.COOKIE);
        tracepanel.profile(trace, Profiler.asked(profile, cookies),
                Profiler.reason(profile, cookies, attrs.containsKey("w3c.parent_span_id")));
        Tracepanel.bind(previous);
        PropagatedContext context = PropagatedContext.getOrEmpty().plus(new TraceContext(trace));
        return ReactivePropagation.propagate(context, Flux.defer(() -> {
            try (PropagatedContext.Scope ignored = context.propagate()) {
                return Flux.from(chain.proceed(request));
            }
        }).doOnNext(response -> response(request, response, trace))
                .doOnError(error -> {
                    trace.failed();
                    trace.attr("http.status_code", 500);
                    trace.error(error, tracepanel.stacks(), false);
                })
                .doFinally(signal -> {
                    try (PropagatedContext.Scope ignored = context.propagate()) {
                        if (signal == SignalType.CANCEL) trace.failed();
                        route(request, trace);
                        tracepanel.finish(trace);
                    }
                }));
    }

    private void response(HttpRequest<?> request, MutableHttpResponse<?> response, Trace trace) {
        route(request, trace);
        request.getUserPrincipal().ifPresent(principal -> trace.user(principal.getName(), null));
        trace.attr("http.status_code", response.code());
        if (response.code() >= 500) {
            trace.failed();
            RouteAttributes.getException(response).ifPresent(error -> trace.error(error, tracepanel.stacks(), false));
        }
        String origin = hosts.resolve(request);
        Widget.headers(trace, origin, prefix).forEach(response::header);
        if (request.getHeaders().contains(HttpHeaders.ORIGIN)) {
            String exposed = response.getHeaders().get(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS);
            response.header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                    exposed == null ? Widget.EXPOSED : exposed + ", " + Widget.EXPOSED);
        }
        if (tracepanel.config().captureRequest()) {
            RequestDetails.responseHeaders(headers(response.getHeaders())).forEach(trace::attr);
            Object body = request.getBody().orElse(null);
            byte[] bytes = body instanceof byte[] data ? data
                    : body instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : null;
            if (bytes != null) RequestDetails.body(request.getContentType().map(Object::toString).orElse(""), bytes, true)
                    .forEach((key, value) -> trace.attr("http.request." + key, value));
        }
        if (response.getContentType().filter(type -> type.getName().equals("text/html")).isEmpty()
                || response.getHeaders().contains(HttpHeaders.CONTENT_ENCODING)) return;
        Object body = response.getBody().orElse(null);
        String html = body instanceof String text ? text
                : body instanceof byte[] bytes ? new String(bytes, response.getCharacterEncoding()) : null;
        if (html == null) return;
        String decorated = Widget.decorate(html, trace, request.getMethodName(), response.code(),
                prefix, "java");
        response.body(body instanceof byte[] ? decorated.getBytes(response.getCharacterEncoding()) : decorated);
        response.getHeaders().remove(HttpHeaders.CONTENT_LENGTH);
        response.getHeaders().remove(HttpHeaders.ETAG);
        response.getHeaders().remove("Content-MD5");
        response.getHeaders().remove("Content-Digest");
    }

    private static void route(HttpRequest<?> request, Trace trace) {
        RouteAttributes.getRouteMatch(request).ifPresent(match -> {
            if (match instanceof UriRouteMatch<?, ?> uri) {
                String pattern = uri.getRouteInfo().getUriMatchTemplate().toString();
                trace.attr("http.route", pattern);
                trace.name(request.getMethodName() + " " + pattern);
            }
            if (match instanceof MethodBasedRouteMatch<?, ?> method) {
                var executable = method.getExecutableMethod();
                trace.attr("code.view", executable.getDeclaringType().getSimpleName() + "#" + executable.getMethodName());
            }
        });
    }

    private Publisher<MutableHttpResponse<?>> console(HttpRequest<?> request, String path) {
        return Mono.<MutableHttpResponse<?>>fromCallable(() -> {
            byte[] body = consoleBody(request);
            if (body.length > Console.MAX_BODY) body = null;
            String query = request.getUri().getRawQuery();
            var remote = request.getRemoteAddress();
            String address = remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
            Console.Answer answer = Console.forward(tracepanel, request.getMethodName(),
                    path + (query == null ? "" : "?" + query), request.getContentType().map(Object::toString).orElse(null),
                    body, address);
            MutableHttpResponse<byte[]> response = HttpResponse.status(io.micronaut.http.HttpStatus.valueOf(answer.status()))
                    .body(answer.body()).contentType(answer.contentType());
            answer.headers().forEach(response::header);
            return response;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private static byte[] consoleBody(HttpRequest<?> request) throws IOException {
        if (request instanceof ServerHttpRequest<?> server) {
            try (InputStream input = server.byteBody().toInputStream()) {
                return input.readNBytes(Console.MAX_BODY + 1);
            }
        }
        return request.getBody(byte[].class).orElse(new byte[0]);
    }

    private static List<Map.Entry<String, String>> headers(HttpHeaders headers) {
        List<Map.Entry<String, String>> values = new ArrayList<>();
        headers.forEach((name, entries) -> entries.forEach(value -> values.add(Map.entry(name, value))));
        return values;
    }
}
