package dev.tracepanel.micronaut;

import dev.tracepanel.core.HttpCalls;
import dev.tracepanel.core.Profiler;
import dev.tracepanel.core.W3c;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.annotation.Filter;
import io.micronaut.http.filter.ClientFilterChain;
import io.micronaut.http.filter.HttpClientFilter;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

@Filter("/**")
@Requires(classes = HttpClientFilter.class, condition = TracepanelEnabled.class)
final class TracepanelClientFilter implements HttpClientFilter {
    @Override
    public Publisher<? extends HttpResponse<?>> doFilter(MutableHttpRequest<?> request, ClientFilterChain chain) {
        return Flux.<HttpResponse<?>>defer(() -> {
            HttpCalls.Call call = HttpCalls.start(request.getMethodName(), request.getUri());
            if (call == null) return Flux.defer(() -> Flux.<HttpResponse<?>>from(chain.proceed(request)));
            if (!request.getHeaders().contains(W3c.HEADER)) request.header(W3c.HEADER, call.traceparent());
            if (call.profile() != null && !request.getHeaders().contains(Profiler.HEADER)) {
                request.header(Profiler.HEADER, call.profile());
            }
            AtomicBoolean finished = new AtomicBoolean();
            return Flux.defer(() -> Flux.<HttpResponse<?>>from(chain.proceed(request)))
                    .doOnNext(response -> { if (finished.compareAndSet(false, true)) call.end(response.code()); })
                    .doOnError(error -> { if (finished.compareAndSet(false, true)) call.failed(error); })
                    .doOnComplete(() -> {
                        if (finished.compareAndSet(false, true)) call.failed(new IllegalStateException("HTTP call completed without a response"));
                    })
                    .doOnCancel(() -> {
                        if (finished.compareAndSet(false, true)) call.failed(new CancellationException("HTTP call cancelled"));
                    });
        });
    }
}
