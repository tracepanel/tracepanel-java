package dev.tracepanel.spring;

import dev.tracepanel.core.Console;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import dev.tracepanel.core.Widget;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Each request a trace, named by its route once Spring matched it ("GET /owners/{id}"), its headers and, on a page,
 * the widget; and the console under the prefix, on the app's own origin.
 */
final class TracepanelFilter extends OncePerRequestFilter {
    // W3C: a caller's trace, which the agent links this request to.
    private static final String TRACE = TracepanelFilter.class.getName() + ".trace";
    private static final Pattern TRACEPARENT = Pattern.compile("^00-([0-9a-f]{32})-([0-9a-f]{16})-[0-9a-f]{2}$");

    private final Tracepanel tracepanel;
    private final String framework;
    private final String runtime;

    TracepanelFilter(Tracepanel tracepanel, String framework, String runtime) {
        this.tracepanel = tracepanel;
        this.framework = framework;
        this.runtime = runtime;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String prefix = tracepanel.config().prefix();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith(prefix) || path.equals(prefix.substring(0, prefix.length() - 1))) {
            console(request, response, path.length() >= prefix.length() ? path.substring(prefix.length()) : "");
            return;
        }
        if (request.getDispatcherType() == DispatcherType.ASYNC) {
            async(request, response, chain);
            return;
        }
        String method = request.getMethod();
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("http.method", method);
        attrs.put("http.path", path);
        attrs.put("service.role", "web");
        attrs.put("tracepanel.framework", framework);
        Matcher caller = TRACEPARENT.matcher(String.valueOf(request.getHeader("traceparent")));
        if (caller.matches()) {
            attrs.put("w3c.trace_id", caller.group(1));
            attrs.put("w3c.parent_span_id", caller.group(2));
        }
        Trace trace = tracepanel.begin("http", method + " " + path, attrs);
        String origin = request.getScheme() + "://" + request.getHeader("Host");
        TracedResponse traced = new TracedResponse(response, () -> headers(trace, request, response, origin));
        // The ids now, so a fetch() caller can open a streamed answer too; the timing once it is known.
        response.setHeader("Tracepanel-Id", trace.id());
        Throwable failure = null;
        try {
            chain.doFilter(request, traced);
        } catch (IOException | ServletException | RuntimeException | Error e) {
            failure = e;
            trace.error(e, tracepanel.stacks(), false);
            throw e;
        } finally {
            if (failure == null && request.isAsyncStarted()) {
                later(trace, request, response, traced, origin);
            } else {
                finish(trace, request, response, traced, origin, failure);
            }
        }
    }

    /**
     * The handler answers later (a suspend function, a Callable, a DeferredResult, a Mono): the trace ends with the
     * response, and goes with the request to the thread that completes it. This one works for no trace meanwhile.
     */
    private void later(Trace trace, HttpServletRequest request, HttpServletResponse response, TracedResponse traced, String origin) {
        request.setAttribute(TRACE, trace);
        traced.passThrough();
        Tracepanel.bind(null);
        request.getAsyncContext().addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) throws IOException {
                finish(trace, request, response, traced, origin, null);
            }

            @Override
            public void onError(AsyncEvent event) {
                if (event.getThrowable() != null) trace.error(event.getThrowable(), tracepanel.stacks(), false);
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                trace.attr("http.timeout", true);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                event.getAsyncContext().addListener(this);
            }
        });
    }

    /** The dispatch that writes an async request's answer, in its trace: the errors and views it records are its. */
    private void async(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Trace previous = Tracepanel.bind(request.getAttribute(TRACE) instanceof Trace trace ? trace : null);
        try {
            chain.doFilter(request, response);
        } finally {
            Tracepanel.bind(previous);
        }
    }

    private void finish(Trace trace, HttpServletRequest request, HttpServletResponse response, TracedResponse traced,
            String origin, Throwable failure) throws IOException {
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (route instanceof String pattern && !pattern.isEmpty()) {
            trace.attr("http.route", pattern);
            trace.name(request.getMethod() + " " + pattern);
        }
        int status = failure != null ? 500 : response.getStatus();
        trace.attr("http.status_code", status);
        if (status >= 500) trace.failed();
        // An exception the app answered with a 4xx (a missing record) was handled; one that failed the request wasn't.
        if (trace.hasErrors()) trace.errorsHandled(status < 500);
        byte[] page = traced.page();
        if (page != null && failure == null && !request.isAsyncStarted()) {
            headers(trace, request, response, origin);
            String html = Widget.decorate(new String(page, traced.charset()), trace, request.getMethod(), status,
                    tracepanel.config().prefix(), runtime);
            byte[] body = html.getBytes(traced.charset());
            response.setContentLength(body.length);
            response.getOutputStream().write(body);
        } else if (!response.isCommitted()) {
            headers(trace, request, response, origin);
        }
        tracepanel.finish(trace);
        if (Tracepanel.current() == trace) Tracepanel.bind(null);
    }

    private void headers(Trace trace, HttpServletRequest request, HttpServletResponse response, String origin) {
        if (response.isCommitted()) return;
        Widget.headers(trace, origin, tracepanel.config().prefix()).forEach(response::setHeader);
        // Across origins (an SPA on its dev server), the page reads them only when the API exposes them.
        if (request.getHeader("Origin") != null) {
            String exposed = response.getHeader("Access-Control-Expose-Headers");
            response.setHeader("Access-Control-Expose-Headers", exposed == null ? Widget.EXPOSED : exposed + ", " + Widget.EXPOSED);
        }
    }

    private void console(HttpServletRequest request, HttpServletResponse response, String path) throws IOException {
        String query = request.getQueryString();
        byte[] body = null;
        if (!request.getMethod().equals("GET")) {
            long length = request.getContentLengthLong();
            body = length > Console.MAX_BODY ? null : request.getInputStream().readNBytes(Console.MAX_BODY + 1);
            if (body != null && body.length > Console.MAX_BODY) body = null;
        }
        Console.Answer answer = Console.forward(tracepanel, request.getMethod(), path + (query == null ? "" : "?" + query),
                request.getContentType(), body, request.getRemoteAddr());
        response.setStatus(answer.status());
        if (answer.contentType() != null) response.setContentType(answer.contentType());
        answer.headers().forEach(response::setHeader);
        response.setContentLength(answer.body().length);
        response.getOutputStream().write(answer.body());
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
