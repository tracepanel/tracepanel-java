package dev.tracepanel.ktor

import dev.tracepanel.core.Config
import dev.tracepanel.core.Console
import dev.tracepanel.core.Trace
import dev.tracepanel.core.W3c
import dev.tracepanel.core.Widget
import dev.tracepanel.integrations.LogbackRecorder
import io.ktor.http.ContentType
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
import io.ktor.server.application.hooks.CallFailed
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
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

/** The whole call, from before the routing to the response sent: the handler runs inside what this wraps it in. */
private object AroundCall : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit) {
        pipeline.intercept(ApplicationCallPipeline.Setup) { handler(call) { proceed() } }
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
    // On in Ktor's development mode (ktor.development, -Dio.ktor.development=true), or when TRACEPANEL_ENABLED says so:
    // the dependency goes into the production build too.
    val explicit = System.getenv("TRACEPANEL_ENABLED")
    if (!(if (explicit.isNullOrBlank()) application.developmentMode else config.enabled())) return@createApplicationPlugin
    val packages = pluginConfig.packages.ifEmpty { appPackages() }
    val tracepanel = Core.start(config, packages)
    val ktorVersion = ApplicationCall::class.java.`package`?.implementationVersion ?: ""
    val kotlin = KotlinVersion.CURRENT
    tracepanel.language("kotlin", kotlin.toString())
    tracepanel.framework("Ktor $ktorVersion".trim())
    val runtime = "Kotlin ${kotlin.major}.${kotlin.minor}" + if (ktorVersion.isEmpty()) "" else " · Ktor ${minor(ktorVersion)}"
    if (present("ch.qos.logback.classic.LoggerContext")) LogbackRecorder.install(tracepanel)
    val prefix = config.prefix()

    // The route that matched, once the routing found it: the trace's name.
    application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        call.attributes.put(ROUTE, routePath(call.route.toString()))
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
        val trace = tracepanel.begin("http", "$method $path", attrs)
        // On this thread only inside the call's coroutines, which put it back on every thread they resume on.
        Core.bind(null)
        call.attributes.put(TRACE, trace)
        call.response.headers.append("Tracepanel-Id", trace.id(), safeOnly = false)
        var failure: Throwable? = null
        try {
            withContext(Core.holder().asContextElement(trace)) { proceed() }
        } catch (error: Throwable) {
            failure = error
            if (!isResponse(error)) trace.error(error, tracepanel.stacks(), false)
            throw error
        } finally {
            call.attributes.getOrNull(ROUTE)?.let {
                trace.attr("http.route", it)
                trace.name("$method $it")
            }
            // An exception leaves before Ktor answers it: the status it will answer with.
            val status = call.response.status()?.value ?: failure?.let(::answeredWith) ?: 200
            trace.attr("http.status_code", status)
            if (status >= 500) trace.failed()
            if (trace.hasErrors()) trace.errorsHandled(status < 500)
            tracepanel.finish(trace)
        }
    }

    // An exception a plugin answered (StatusPages): the trace keeps it, unless it is the 4xx answer itself.
    on(CallFailed) { call, cause ->
        val trace = call.attributes.getOrNull(TRACE)
        if (trace != null && !isResponse(cause)) trace.error(cause, tracepanel.stacks(), false)
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
        val type = content.contentType ?: return@on
        if (!type.match(ContentType.Text.Html)) return@on
        val status = (content.status ?: call.response.status() ?: HttpStatusCode.OK).value
        val decorate = { html: String -> Widget.decorate(html, trace, call.request.httpMethod.value, status, prefix, runtime) }
        when (content) {
            is TextContent -> transformBodyTo(TextContent(decorate(content.text), type, content.status))
            is ByteArrayContent -> transformBodyTo(
                TextContent(decorate(content.bytes().toString(type.charset() ?: Charsets.UTF_8)), type, content.status),
            )
            is OutgoingContent.WriteChannelContent -> transformBodyTo(Decorated(content, type, decorate))
            else -> Unit
        }
    }
}

/** A page written as it is made (kotlinx.html's respondHtml): held whole, then written with the widget. */
private class Decorated(
    private val page: OutgoingContent.WriteChannelContent,
    override val contentType: ContentType,
    private val decorate: (String) -> String,
) : OutgoingContent.WriteChannelContent() {
    override val status: HttpStatusCode? get() = page.status

    override suspend fun writeTo(channel: ByteWriteChannel) = coroutineScope {
        val held = ByteChannel()
        launch {
            page.writeTo(held)
            held.flushAndClose()
        }
        val charset = contentType.charset() ?: Charsets.UTF_8
        val html = held.readRemaining().readByteArray().toString(charset)
        channel.writeFully(decorate(html).toByteArray(charset))
    }
}

/** The console's request, sent on to the project's agent: the app answers what its agent answers. */
private suspend fun console(tracepanel: Core, call: ApplicationCall, path: String) {
    val method = call.request.httpMethod.value
    val query = call.request.local.uri.substringAfter('?', "")
    val body = if (method == "GET") null else call.receiveChannel().readRemaining(Console.MAX_BODY.toLong() + 1).readByteArray()
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

/** Ktor's exceptions that are the answer, below 500 (NotFoundException, BadRequestException): not errors. */
private fun isResponse(error: Throwable): Boolean = answeredWith(error) < 500

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
