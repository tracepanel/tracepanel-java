package dev.tracepanel.spring;

import dev.tracepanel.core.HttpCalls;
import dev.tracepanel.core.Profiler;
import dev.tracepanel.core.W3c;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * The app's WebClient calls recorded: each WebClient.Builder Spring Boot hands out gets a filter that starts the
 * call when the app subscribes to it (in the trace it runs for then) and ends it with the answer.
 */
final class WebClients implements BeanPostProcessor {
    private static final ExchangeFilterFunction FILTER = (request, next) -> Mono.defer(() -> {
        HttpCalls.Call call = HttpCalls.start(request.method().name(), request.url());
        if (call == null) return next.exchange(request);
        ClientRequest traced = traced(request, call);
        return next.exchange(traced)
                .doOnNext(response -> call.end(response.statusCode().value()))
                .doOnError(call::failed);
    });

    /** The request with the call's traceparent and what its trace asks to profile, unless the app set its own. */
    private static ClientRequest traced(ClientRequest request, HttpCalls.Call call) {
        boolean parent = request.headers().getFirst(W3c.HEADER) == null;
        String profile = call.profile();
        boolean asks = profile != null && request.headers().getFirst(Profiler.HEADER) == null;
        if (!parent && !asks) return request;
        ClientRequest.Builder builder = ClientRequest.from(request);
        if (parent) builder.header(W3c.HEADER, call.traceparent());
        if (asks) builder.header(Profiler.HEADER, profile);
        return builder.build();
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (bean instanceof WebClient.Builder builder) return builder.filter(FILTER);
        return bean;
    }
}
