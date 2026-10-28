package dev.tracepanel.core;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * The JDK's HttpClient with each call recorded (HttpCalls): send and sendAsync are http.client spans of the trace the
 * app makes them in, with a traceparent on the request; everything else is the client's own.
 */
final class TracedHttpClient extends HttpClient {
    private final HttpClient client;

    TracedHttpClient(HttpClient client) {
        this.client = client;
    }

    HttpClient unwrap() {
        return client;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpCalls.Call call = HttpCalls.start(request.method(), request.uri());
        if (call == null) return client.send(request, handler);
        try {
            HttpResponse<T> response = HttpCalls.nested(() -> client.send(traced(request, call), handler));
            call.end(response.statusCode());
            return response;
        } catch (IOException | InterruptedException | RuntimeException e) {
            call.failed(e);
            throw e;
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        return sendAsync(request, handler, null);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
        HttpCalls.Call call = HttpCalls.start(request.method(), request.uri());
        if (call == null) return client.sendAsync(request, handler, push);
        return client.sendAsync(traced(request, call), handler, push).whenComplete((response, error) -> {
            if (error != null) call.failed(error);
            else call.end(response.statusCode());
        });
    }

    /** The request with the call's traceparent and what its trace asks to profile, unless the app set its own. */
    private static HttpRequest traced(HttpRequest request, HttpCalls.Call call) {
        boolean parent = request.headers().firstValue(W3c.HEADER).isEmpty();
        String profile = call.profile();
        boolean asks = profile != null && request.headers().firstValue(Profiler.HEADER).isEmpty();
        if (!parent && !asks) return request;
        HttpRequest.Builder builder = HttpRequest.newBuilder(request, (name, value) -> true);
        if (parent) builder.header(W3c.HEADER, call.traceparent());
        if (asks) builder.header(Profiler.HEADER, profile);
        return builder.build();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return client.cookieHandler();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return client.connectTimeout();
    }

    @Override
    public Redirect followRedirects() {
        return client.followRedirects();
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return client.proxy();
    }

    @Override
    public SSLContext sslContext() {
        return client.sslContext();
    }

    @Override
    public SSLParameters sslParameters() {
        return client.sslParameters();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return client.authenticator();
    }

    @Override
    public Version version() {
        return client.version();
    }

    @Override
    public Optional<Executor> executor() {
        return client.executor();
    }

    @Override
    public java.net.http.WebSocket.Builder newWebSocketBuilder() {
        return client.newWebSocketBuilder();
    }

    // Java 21's lifecycle methods, the client's own (built for Java 17, so not marked as overrides).

    public void shutdown() {
        lifecycle("shutdown");
    }

    public void shutdownNow() {
        lifecycle("shutdownNow");
    }

    public boolean isTerminated() {
        return Boolean.TRUE.equals(lifecycle("isTerminated"));
    }

    public boolean awaitTermination(Duration duration) throws InterruptedException {
        try {
            return Boolean.TRUE.equals(HttpClient.class.getMethod("awaitTermination", Duration.class).invoke(client, duration));
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof InterruptedException interrupted) throw interrupted;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            return true;
        }
    }

    public void close() {
        lifecycle("close");
    }

    private Object lifecycle(String name) {
        try {
            return HttpClient.class.getMethod(name).invoke(client);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
