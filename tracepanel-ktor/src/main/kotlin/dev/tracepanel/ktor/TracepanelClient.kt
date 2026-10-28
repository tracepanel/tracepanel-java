package dev.tracepanel.ktor

import dev.tracepanel.core.HttpCalls
import dev.tracepanel.core.Profiler
import dev.tracepanel.core.W3c
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import java.net.URI

/**
 * A Ktor HttpClient's calls, recorded: HttpClient { install(TracepanelClient) }. Each call the app makes in a trace
 * is an http.client span (GET api.example.com/users/1) with its status and the app's line, and carries a
 * traceparent, so the app it reaches links its trace to the call, and X-Tracepanel-Profile when the call's trace was
 * asked to be profiled, so that app profiles its trace too.
 */
val TracepanelClient = createClientPlugin("TracepanelClient") {
    on(Send) { request ->
        val call = HttpCalls.start(request.method.value, URI(request.url.buildString())) ?: return@on proceed(request)
        if (!request.headers.contains(W3c.HEADER)) request.headers.append(W3c.HEADER, call.traceparent())
        // A profile its trace's request asked for: the app it reaches profiles its trace too.
        val profile = call.profile()
        if (profile != null && !request.headers.contains(Profiler.HEADER)) request.headers.append(Profiler.HEADER, profile)
        try {
            val sent = proceed(request)
            call.end(sent.response.status.value)
            sent
        } catch (error: Throwable) {
            call.failed(error)
            throw error
        }
    }
}
