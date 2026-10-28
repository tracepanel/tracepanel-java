package dev.tracepanel.ktor

import dev.tracepanel.core.Config
import dev.tracepanel.core.Console
import dev.tracepanel.core.HttpCalls
import dev.tracepanel.core.Profiler
import dev.tracepanel.core.RequestDetails
import dev.tracepanel.core.Trace
import dev.tracepanel.core.W3c
import dev.tracepanel.core.Widget
import dev.tracepanel.integrations.Log4j2Recorder
import dev.tracepanel.integrations.LogbackRecorder
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.charset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.Hook
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ReceiveRequestBytes
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.queryString
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import java.util.AbstractMap
import dev.tracepanel.core.Tracepanel as Core

/** The plugin's settings: what the console calls the app, and the packages that are its own code. */
class TracepanelConfig {
    /** The app's name in the console: TRACEPANEL_SERVICE, else this, else ktor.application.id, else the directory's. */
    var service: String? = null

    /** The app's own packages: their frames are its code in stacks and code.origin. By default the module's. */
    var packages: List<String> = emptyList()
}

private val TRACE = AttributeKey<Trace>("tracepanel.trace")
private val ROUTE = AttributeKey<String>("tracepanel.route")
private val FAILURE = AttributeKey<Throwable>("tracepanel.failure")
private val VIEW = AttributeKey<Map<String, Any>>("tracepanel.view")
private val BODY = AttributeKey<RequestDetails.Kept>("tracepanel.body")

/**
 * The whole call, from before the routing to the response sent, and around the plugins that answer an exception
 * (StatusPages): the handler runs inside what this wraps it in, and the status is the one the call answered with.
 */
private object AroundCall : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    private val phase = PipelinePhase("Tracepanel")

    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit) {
        // Before every phase there is now: a plugin's that catches exceptions (CallFailed's) is inserted later, inside.
        pipeline.insertPhaseBefore(pipeline.items.first(), phase)
        pipeline.intercept(phase) { handler(call) { proceed() } }
    }
}

/** An exception the call's handler threw, inside the plugins that may answer it: whether it is an error, its status says. */
private object Thrown : Hook<suspend (ApplicationCall, Throwable) -> Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall, Throwable) -> Unit) {
        pipeline.intercept(ApplicationCallPipeline.Setup) {
            try {
                proceed()
            } catch (cause: Throwable) {
                handler(call, cause)
                throw cause
            }
        }
    }
}

/**
 * Tracepanel in a Ktor app: install(Tracepanel). Each call is a trace named by its route (GET /books/{id}), its
 * coroutines carry it to every thread they run on (withContext(Dispatchers.IO), async), and its errors, logs and the
 * SQL of a DataSource from Jdbc.dataSource are in it; an HTML page gets the widget, and the console answers under
 * /__tracepanel__/. On in Ktor's development mode, or with TRACEPANEL_ENABLED=1; TRACEPANEL_ENABLED=0 turns it off.
 */
val Tracepanel = createApplicationPlugin("Tracepanel", ::TracepanelConfig) {
    val config = Config.load(pluginConfig.service ?: application.environment.config.propertyOrNull("ktor.application.id")?.getString())
    // On in Ktor's development mode (ktor.development, -Dio.ktor.development=true), or when TRACEPANEL_ENABLED says so
    // (tracepanel.enabled, as tracepanel-junit sets it): the dependency goes into the production build too.
    val explicit = System.getenv("TRACEPANEL_ENABLED").takeUnless { it.isNullOrBlank() } ?: System.getProperty("tracepanel.enabled")
    val on = if (explicit.isNullOrBlank()) application.developmentMode else explicit.lowercase() !in setOf("0", "false", "no", "off")
    if (!on) return@createApplicationPlugin
    val packages = pluginConfig.packages.ifEmpty { appPackages() }
    val tracepanel = Core.start(config, packages)
    val ktorVersion = ApplicationCall::class.java.`package`?.implementationVersion ?: ""
    val kotlin = KotlinVersion.CURRENT
    tracepanel.language("kotlin", kotlin.toString())
    tracepanel.framework("Ktor $ktorVersion".trim())
    val runtime = "Kotlin ${kotlin.major}.${kotlin.minor}" + if (ktorVersion.isEmpty()) "" else " · Ktor ${minor(ktorVersion)}"
    if (present("ch.qos.logback.classic.LoggerContext")) LogbackRecorder.install(tracepanel)
    if (present("org.apache.logging.log4j.core.LoggerContext")) Log4j2Recorder.install(tracepanel)
    val prefix = config.prefix()
    val sessions = present("io.ktor.server.sessions.SessionsKt")

    // The route that matched, once the routing found it: the trace's name. Its handler, a lambda with no name of its
    // own, is a view span named by the route (GET /books/{id}), as is the trace's code.view; the console calls it the
    // handler. What it runs, on any thread, goes under it.
    application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        val route = routePath(call.route.toString())
        call.attributes.put(ROUTE, route)
        val trace = call.attributes.getOrNull(TRACE) ?: return@subscribe
        val name = "${call.request.httpMethod.value} $route"
        trace.attr("code.view", name)
        call.attributes.put(VIEW, trace.begin("view", name, emptyMap()))
    }
    application.monitor.subscribe(RoutingRoot.RoutingCallFinished) { call ->
        val view = call.attributes.getOrNull(VIEW) ?: return@subscribe
        call.attributes.getOrNull(TRACE)?.end(view, call.attributes.contains(FAILURE))
    }

    on(AroundCall) { call, proceed ->
        val path = call.request.path()
        if (path.startsWith(prefix) || path == prefix.dropLast(1)) {
            console(tracepanel, call, path.removePrefix(prefix.dropLast(1)).removePrefix("/"))
            return@on
        }
        val method = call.request.httpMethod.value
        val attrs = linkedMapOf<String, Any>(
            "http.method" to method,
            "http.path" to path,
            "service.role" to "web",
        )
        attrs.putAll(W3c.incoming(call.request.headers[W3c.HEADER]))
        // A test's call of the app without a traceparent (testApplication's client): a call of the test's trace.
        val testCall = if (attrs.containsKey("w3c.trace_id")) null else testCall(call, method)
        testCall?.let { attrs.putAll(W3c.incoming(it.traceparent())) }
        val cookies = call.request.headers.getAll(HttpHeaders.Cookie)?.joinToString("; ")
        // What the call carried, for the console's Request tab; its body as the handler reads it (ReceiveRequestBytes).
        if (config.captureRequest()) {
            val headers = call.request.headers.entries().flatMap { (name, values) -> values.map { AbstractMap.SimpleEntry(name, it) } }
            attrs.putAll(RequestDetails.requestHeaders(headers))
            attrs.putAll(RequestDetails.query(call.request.queryString()))
            attrs.putAll(RequestDetails.cookies(cookies))
            val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1
            val chunked = call.request.headers[HttpHeaders.TransferEncoding]?.contains("chunked", ignoreCase = true) == true
            if (length > 0 || chunked) call.attributes.put(BODY, RequestDetails.Kept(length))
        }
        val trace = tracepanel.begin("http", "$method $path", attrs)
        // Asked by a header or the panel's cookie: where this call's time goes in code.
        tracepanel.profile(trace, Profiler.asked(call.request.headers[Profiler.HEADER], cookies))
        // On this thread only inside the call's coroutines, which put it back on every thread they resume on: the
        // thread works for the trace it did before (none, or the test's whose client made the call).
        Core.bind(trace.outer())
        call.attributes.put(TRACE, trace)
        call.response.headers.append("Tracepanel-Id", trace.id(), safeOnly = false)
        var failure: Throwable? = null
        try {
            withContext(Core.holder().asContextElement(trace)) { proceed() }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            call.attributes.getOrNull(ROUTE)?.let {
                trace.attr("http.route", it)
                trace.name("$method $it")
            }
            // An exception that leaves the call goes before Ktor answers it: the status it will answer with.
            val status = call.response.status()?.value ?: failure?.let(::answeredWith) ?: 200
            trace.attr("http.status_code", status)
            // The handler's exception is an error when the call failed with it: one a plugin answered with a 4xx
            // (StatusPages' 404, 409, 422) is the answer, as Ktor's own NotFoundException is.
            val thrown = failure ?: call.attributes.getOrNull(FAILURE)
            if (thrown != null && status >= 500) trace.error(thrown, tracepanel.stacks(), false)
            if (status >= 500) trace.failed()
            if (trace.hasErrors()) trace.errorsHandled(status < 500)
            if (config.captureRequest()) {
                val headers = call.response.headers.allValues().entries()
                    .flatMap { (name, values) -> values.map { AbstractMap.SimpleEntry(name, it) } }
                RequestDetails.responseHeaders(headers).forEach(trace::attr)
                call.attributes.getOrNull(BODY)?.attrs(call.request.headers[HttpHeaders.ContentType])?.forEach(trace::attr)
                // The session as the call left it (the Sessions plugin's), as Spring's HttpSession is recorded.
                if (sessions) runCatching { RequestDetails.session(sessionItems(call)) }.getOrNull()?.forEach(trace::attr)
            }
            tracepanel.finish(trace)
            testCall?.end(status)
        }
    }

    // The body as the handler reads it (receive, receiveText, a form): its start kept as it passes, nothing more read.
    on(ReceiveRequestBytes) { call, body ->
        val kept = call.attributes.getOrNull(BODY) ?: return@on body
        call.writer {
            val buffer = ByteArray(8192)
            while (true) {
                val read = body.readAvailable(buffer, 0, buffer.size)
                if (read == -1) break
                kept.add(buffer, 0, read)
                channel.writeFully(buffer, 0, read)
                channel.flush()
            }
        }.channel
    }

    on(Thrown) { call, cause ->
        if (!call.attributes.contains(FAILURE)) call.attributes.put(FAILURE, cause)
    }

    // The trace's headers on every answer, and on a page the widget, with the status and time it went out with.
    on(ResponseBodyReadyForSend) { call, content ->
        val trace = call.attributes.getOrNull(TRACE) ?: return@on
        val origin = "${call.request.local.scheme}://${call.request.headers[HttpHeaders.Host]}"
        Widget.headers(trace, origin, prefix).forEach { (name, value) ->
            if (!call.response.headers.contains(name)) call.response.headers.append(name, value, safeOnly = false)
        }
        if (call.request.headers[HttpHeaders.Origin] != null) {
            call.response.headers.append(HttpHeaders.AccessControlExposeHeaders, Widget.EXPOSED, safeOnly = false)
        }
        val status = (content.status ?: call.response.status() ?: HttpStatusCode.OK).value
        // A redirect that answers a page's navigation (a form's POST): the page it leads to lists this call first.
        val redirect = Widget.redirectCookie(
            trace.id(), status, call.request.headers["Sec-Fetch-Mode"], call.request.headers[HttpHeaders.Accept],
            call.request.headers.getAll(HttpHeaders.Cookie)?.joinToString("; "),
        )
        if (redirect != null && call.response.headers.values(HttpHeaders.SetCookie).none { it.startsWith(Widget.REDIRECTS + "=") }) {
            call.response.headers.append(HttpHeaders.SetCookie, redirect, safeOnly = false)
        }
        val type = content.contentType ?: return@on
        if (!type.match(ContentType.Text.Html)) return@on
        val method = call.request.httpMethod.value
        val decorate = { html: String -> Widget.decorate(html, trace, method, status, prefix, runtime) }
        // A page written as it is made goes out so, the widget put in as it passes; one compressed or of a declared
        // size is left as it is.
        val streamed = {
            val encoding = content.headers[HttpHeaders.ContentEncoding] ?: call.response.headers[HttpHeaders.ContentEncoding]
            if (Widget.streamable(type.toString(), encoding, content.contentLength != null)) {
                Widget.Stream(Widget.script(trace, method, status, prefix, runtime))
            } else {
                null
            }
        }
        when (content) {
            is TextContent -> transformBodyTo(TextContent(decorate(content.text), type, content.status))
            is ByteArrayContent -> transformBodyTo(
                TextContent(decorate(content.bytes().toString(type.charset() ?: Charsets.UTF_8)), type, content.status),
            )
            is OutgoingContent.WriteChannelContent -> streamed()?.let { script ->
                transformBodyTo(Streamed(content, type, script) { scope ->
                    ByteChannel().also { held ->
                        scope.launch {
                            content.writeTo(held)
                            held.flushAndClose()
                        }
                    }
                })
            }
            is OutgoingContent.ReadChannelContent -> streamed()?.let { script ->
                transformBodyTo(Streamed(content, type, script) { content.readFrom() })
            }
            else -> Unit
        }
    }
}

/** The call of the running test's trace this call is, when a test made it in this process; else null. */
private fun testCall(call: ApplicationCall, method: String): HttpCalls.Call? {
    val test = Core.tests()?.caller() ?: return null
    val local = call.request.local
    val query = call.request.queryString()
    val url = "${local.scheme}://${call.request.headers[HttpHeaders.Host] ?: local.serverHost}${call.request.path()}" +
        if (query.isEmpty()) "" else "?$query"
    return runCatching { HttpCalls.start(test, method, java.net.URI(url)) }.getOrNull()
}

/**
 * Names the signed-in user on the call's trace, as the app's authentication knows them: their id and their username
 * (null for none), which stays on this machine. Without Tracepanel on, it does nothing.
 *
 *     call.tracepanelUser(user.id.toString(), user.email)
 */
fun ApplicationCall.tracepanelUser(id: String, username: String?) {
    attributes.getOrNull(TRACE)?.user(id, username)
}

/**
 * A page that goes out as it is made (kotlinx.html's respondHtml, respondTextWriter, a channel): each piece as it comes,
 * through the widget's script, which goes in right after the head opens.
 */
private class Streamed(
    private val page: OutgoingContent,
    override val contentType: ContentType,
    private val script: Widget.Stream,
    private val source: suspend (CoroutineScope) -> ByteReadChannel,
) : OutgoingContent.WriteChannelContent() {
    override val status: HttpStatusCode? get() = page.status
    override val headers: Headers get() = page.headers

    override suspend fun writeTo(channel: ByteWriteChannel) = coroutineScope {
        val pieces = source(this)
        val buffer = ByteArray(8192)
        while (true) {
            val read = pieces.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            val out = script.feed(buffer, 0, read)
            if (out.isNotEmpty()) {
                channel.writeFully(out)
                channel.flush()
            }
        }
        channel.writeFully(script.end())
    }
}

/** The console's request, sent on to the project's agent: the app answers what its agent answers. */
private suspend fun console(tracepanel: Core, call: ApplicationCall, path: String) {
    val method = call.request.httpMethod.value
    val query = call.request.local.uri.substringAfter('?', "")
    val body = if (method == "GET") null else call.receiveChannel().readBuffer(Console.MAX_BODY.toLong() + 1).readByteArray()
    val answer = Console.forward(
        tracepanel, method, path + if (query.isEmpty()) "" else "?$query",
        call.request.contentType().takeIf { it != ContentType.Any }?.toString(),
        body?.takeIf { it.size <= Console.MAX_BODY }, call.request.local.remoteAddress,
    )
    answer.headers().forEach { (name, value) -> call.response.headers.append(name, value, safeOnly = false) }
    call.respondBytes(
        answer.body(),
        answer.contentType()?.let(ContentType::parse) ?: ContentType.Application.OctetStream,
        HttpStatusCode.fromValue(answer.status()),
    )
}

/** The status Ktor answers an exception with: 404, 400 or 415 for its own HTTP exceptions, else 500. */
private fun answeredWith(error: Throwable): Int {
    var type: Class<*>? = error.javaClass
    while (type != null) {
        when (type.name) {
            "io.ktor.server.plugins.NotFoundException" -> return 404
            "io.ktor.server.plugins.BadRequestException" -> return 400
            "io.ktor.server.plugins.UnsupportedMediaTypeException" -> return 415
        }
        type = type.superclass
    }
    return 500
}

/** "/books/{id}/(method:GET)" as Ktor writes a route: "/books/{id}". */
internal fun routePath(route: String): String {
    val path = route.split('/').filter { it.isNotEmpty() && !it.startsWith("(") }.joinToString("/", prefix = "/")
    return path
}

/** The app's packages, from where the app's module installed the plugin: its first two names (com.shop). */
private fun appPackages(): List<String> {
    val caller = StackWalker.getInstance().walk { frames ->
        frames.map { it.className }.filter { name ->
            !name.startsWith("dev.tracepanel.") && !name.startsWith("io.ktor.") && !name.startsWith("kotlin") &&
                !name.startsWith("java.") && !name.startsWith("jdk.") && !name.startsWith("sun.")
        }.findFirst()
    }
    return caller.map { name ->
        val parts = name.substringBeforeLast('.', "").split('.').filter { it.isNotEmpty() }
        listOfNotNull(parts.take(2).joinToString(".").ifEmpty { null })
    }.orElse(emptyList())
}

private fun minor(version: String): String = version.split('.').take(2).joinToString(".")

private fun present(name: String): Boolean =
    try {
        Class.forName(name, false, TracepanelConfig::class.java.classLoader)
        true
    } catch (e: ClassNotFoundException) {
        false
    }
