package dev.tracepanel.spring;

import dev.tracepanel.core.HttpCalls;
import dev.tracepanel.core.W3c;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** A RestClient's or a RestTemplate's call to an HTTP API, an http.client span with a traceparent on the request. */
final class TracedRequests implements ClientHttpRequestInterceptor {
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        HttpCalls.Call call = HttpCalls.start(request.getMethod().name(), request.getURI());
        if (call == null) return execution.execute(request, body);
        // The app's own traceparent, if it set one, is the one that goes.
        if (request.getHeaders().getFirst(W3c.HEADER) == null) request.getHeaders().set(W3c.HEADER, call.traceparent());
        try {
            ClientHttpResponse response = execution.execute(request, body);
            call.end(response.getStatusCode().value());
            return response;
        } catch (IOException | RuntimeException e) {
            call.failed(e);
            throw e;
        }
    }
}
