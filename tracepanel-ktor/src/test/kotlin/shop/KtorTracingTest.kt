package shop

import dev.tracepanel.core.Jdbc
import dev.tracepanel.ktor.Tracepanel
import dev.tracepanel.ktor.TracepanelClient
import dev.tracepanel.ktor.tracepanelUser
import dev.tracepanel.testing.FakeAgent
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondTextWriter
import io.ktor.utils.io.writeFully
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.html.body
import kotlinx.html.head
import kotlinx.html.p
import kotlinx.html.title
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PAGE = "<!doctype html><html><head><title>t</title></head><body>x</body></html>"

/** The streamed page waits here after its first piece, until the client has read it. */
private var firstRead = CompletableDeferred<Unit>()

private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { bytes ->
    GZIPOutputStream(bytes).use { it.write(text.toByteArray()) }
}.toByteArray()

/** The app's own exceptions, which StatusPages answers. */
class OutOfStock(message: String) : RuntimeException(message)

class PriceMissing(message: String) : RuntimeException(message)

/** A Ktor app with the plugin: what the agent gets, and what the page and a caller see. */
class KtorTracingTest {
    companion object {
        private val log = LoggerFactory.getLogger(KtorTracingTest::class.java)
        private val db = Jdbc.dataSource(JdbcDataSource().apply { setURL("jdbc:h2:mem:books;DB_CLOSE_DELAY=-1") }, "default")

        @JvmStatic
        @BeforeAll
        fun agent() {
            FakeAgent.start()
            db.connection.use { it.createStatement().execute("CREATE TABLE IF NOT EXISTS book (id INT PRIMARY KEY, title VARCHAR(50))") }
            db.connection.use { it.createStatement().execute("MERGE INTO book KEY (id) VALUES (1, 'One'), (2, 'Two'), (3, 'Three')") }
        }

        private fun title(id: Int): String = db.connection.use { connection ->
            connection.prepareStatement("SELECT title FROM book WHERE id = ?").use { statement ->
                statement.setInt(1, id)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else "?" }
            }
        }
    }

    private fun app(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val appClient = createClient { install(TracepanelClient) }
        application {
            install(Tracepanel) { packages = listOf("shop") }
            install(StatusPages) {
                exception<OutOfStock> { call, cause -> call.respondText(cause.message ?: "", status = HttpStatusCode.Conflict) }
                exception<PriceMissing> { call, _ -> call.respondText("sorry", status = HttpStatusCode.InternalServerError) }
            }
            routing {
                get("/books/{id}") {
                    val id = call.parameters["id"]!!.toInt()
                    log.info("book {}", id)
                    val first = withContext(Dispatchers.IO) { title(id) }
                    delay(10)
                    val others = coroutineScope { (1..3).map { async(Dispatchers.IO) { title(it) } }.awaitAll() }
                    call.respondText("$first ${others.size}")
                }
                get("/page") {
                    call.respondText("<!doctype html><html><head><title>Books</title></head><body>books</body></html>", ContentType.Text.Html)
                }
                get("/dsl") {
                    call.respondHtml {
                        head { title("Books") }
                        body { p { +"books" } }
                    }
                }
                get("/missing") { throw NotFoundException("no such book") }
                get("/boom") { error("the shelf is on fire") }
                get("/me") {
                    call.tracepanelUser("42", "ada")
                    call.respondText("ada")
                }
                get("/sold-out") { throw OutOfStock("only 2 left") }
                get("/no-price") { throw PriceMissing("no price for book 3") }
                get("/relay") { call.respondText(appClient.get("/books/2").bodyAsText()) }
            }
        }
        block()
    }

    @Suppress("UNCHECKED_CAST")
    private fun attrs(trace: Map<String, Any>) = trace["attrs"] as Map<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun spans(trace: Map<String, Any>, kind: String) = (trace["spans"] as List<Map<String, Any>>).filter { it["kind"] == kind }

    @Test
    fun a_call_is_a_trace_named_by_its_route_with_the_queries_its_coroutines_ran_on_any_thread() = app {
        val response = client.get("/books/1")
        val trace = FakeAgent.traceAt("/books/1")

        assertEquals("One 3", response.bodyAsText())
        assertEquals(trace["id"], response.headers["Tracepanel-Id"])
        assertEquals("GET /books/{id}", trace["name"])
        assertEquals("/books/{id}", attrs(trace)["http.route"])
        assertEquals(200, attrs(trace)["http.status_code"])
        assertEquals("kotlin", attrs(trace)["telemetry.sdk.language"])
        assertTrue(attrs(trace)["tracepanel.framework"].toString().startsWith("Ktor 3."), attrs(trace).toString())
        assertEquals(4, spans(trace, "sql").size)
        assertTrue(spans(trace, "sql").all { (it["attrs"] as Map<*, *>)["code.origin"].toString().contains("KtorTracingTest.kt") })

        val view = spans(trace, "view").single()
        assertEquals("GET /books/{id}", view["name"])
        assertEquals("GET /books/{id}", attrs(trace)["code.view"])
        assertEquals(listOf(view["id"]), spans(trace, "sql").map { it["parent_id"] }.distinct())
        @Suppress("UNCHECKED_CAST")
        val logs = trace["logs"] as List<Map<String, Any>>
        assertEquals(listOf("book 1"), logs.map { it["message"] })
    }

    @Test
    fun a_page_gets_the_widget_however_it_is_written() = app {
        for (path in listOf("/page", "/dsl")) {
            val page = client.get(path).bodyAsText()
            val trace = FakeAgent.traceAt(path)

            assertTrue(page.contains("widget.min.js") && page.contains(trace["id"] as String), page)
            assertTrue(page.contains("data-tracepanel-runtime=\"Kotlin "), page)
            assertTrue(page.contains("books"), page)
        }
    }

    /** The app on a real server, CIO: the test host hands a response to its client only once it is complete. */
    private fun served(block: (String) -> Unit) = runBlocking {
        val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(Tracepanel) { packages = listOf("shop") }
            routing {
                get("/streamed") {
                    call.respondTextWriter(ContentType.Text.Html) {
                        write("<!doctype html><html><he")
                        flush()
                        // Until the client has read the first piece: a page held whole would never let it.
                        val late = if (withTimeoutOrNull(10_000) { firstRead.await() } == null) "(the first piece never arrived)" else ""
                        write("${late}ad><title>t</title></head><body>x</body></html>")
                    }
                }
                get("/streamed/compressed") {
                    call.response.header(HttpHeaders.ContentEncoding, "gzip")
                    call.respondBytesWriter(ContentType.Text.Html) {
                        val page = gzip(PAGE)
                        writeFully(page, 0, 10)
                        flush()
                        writeFully(page, 10, page.size)
                    }
                }
            }
        }.start(wait = false)
        try {
            block("http://127.0.0.1:${server.engine.resolvedConnectors().first().port}")
        } finally {
            server.stop(0, 0)
        }
    }

    @Test
    fun a_streamed_page_goes_out_as_it_is_written_with_the_widget_after_its_head_opens() = served { base ->
        firstRead = CompletableDeferred()
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("$base/streamed")).build(), BodyHandlers.ofInputStream())
        val (first, rest) = response.body().use { body ->
            val first = body.readNBytes("<!doctype html><html>".length).decodeToString()
            firstRead.complete(Unit)
            first to body.readAllBytes().decodeToString()
        }
        val trace = FakeAgent.traceAt("/streamed")

        assertEquals(trace["id"], response.headers().firstValue("Tracepanel-Id").orElse(null))
        assertTrue(response.headers().firstValue("Server-Timing").orElse("").contains("total;dur="), response.headers().toString())
        assertEquals(null, response.headers().firstValue("Content-Length").orElse(null))

        // Only the tag cut between the pieces waited: what came before it was out already.
        assertEquals("<!doctype html><html>", first)
        assertTrue(rest.startsWith("<head><script src=\"/__tracepanel__/widget.min.js\"") && rest.contains(trace["id"] as String), rest)
        assertTrue(rest.endsWith("></script><title>t</title></head><body>x</body></html>"), rest)
    }

    @Test
    fun a_compressed_page_streamed_is_left_as_it_is_with_the_badge_s_headers() = served { base ->
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("$base/streamed/compressed")).build(), BodyHandlers.ofByteArray())

        assertTrue(response.headers().firstValue("Tracepanel-Id").isPresent, response.headers().toString())
        assertEquals(gzip(PAGE).toList(), response.body().toList())
    }

    @Test
    fun a_404_answer_is_not_an_error_and_an_exception_that_fails_the_call_is() = app {
        assertEquals(HttpStatusCode.NotFound, client.get("/missing").status)
        assertEquals(HttpStatusCode.InternalServerError, client.get("/boom").status)
        val missing = FakeAgent.traceAt("/missing")
        val boom = FakeAgent.traceAt("/boom")

        assertEquals("ok", missing["status"])
        assertEquals(emptyList<Any>(), missing["errors"])
        assertEquals("error", boom["status"])
        @Suppress("UNCHECKED_CAST")
        val errors = boom["errors"] as List<Map<String, Any>>
        assertEquals(listOf("java.lang.IllegalStateException"), errors.map { it["type"] })
    }

    @Test
    fun an_exception_status_pages_answers_with_a_4xx_is_not_an_error_and_one_it_answers_with_a_5xx_is() = app {
        assertEquals(HttpStatusCode.Conflict, client.get("/sold-out").status)
        assertEquals(HttpStatusCode.InternalServerError, client.get("/no-price").status)
        val soldOut = FakeAgent.traceAt("/sold-out")
        val noPrice = FakeAgent.traceAt("/no-price")

        assertEquals("ok", soldOut["status"])
        assertEquals(409, attrs(soldOut)["http.status_code"])
        assertEquals(emptyList<Any>(), soldOut["errors"])

        assertEquals("error", noPrice["status"])
        @Suppress("UNCHECKED_CAST")
        val errors = noPrice["errors"] as List<Map<String, Any>>
        assertEquals(listOf(listOf("shop.PriceMissing", "no price for book 3", false)), errors.map { listOf(it["type"], it["message"], it["handled"]) })
    }

    @Test
    fun the_app_names_the_signed_in_user() = app {
        client.get("/me")
        val trace = FakeAgent.traceAt("/me")

        assertEquals("42", attrs(trace)["user.id"])
        assertEquals("ada", attrs(trace)["user.username"])
    }

    @Test
    fun a_call_to_an_http_api_is_a_span_and_the_app_it_reaches_links_its_trace_to_it() = app {
        client.get("/relay")
        val caller = FakeAgent.traceAt("/relay")
        val called = FakeAgent.traceAt("/books/2")
        val call = spans(caller, "http.client").single()

        assertEquals("GET localhost/books/2", call["name"])
        assertEquals(200, (call["attrs"] as Map<*, *>)["http.response.status_code"])
        assertEquals((call["attrs"] as Map<*, *>)["w3c.span_id"], attrs(called)["w3c.parent_span_id"])
    }

    @Test
    fun the_console_answers_under_the_prefix() = app {
        val answer = client.get("/__tracepanel__/api/traces?limit=1") { header("X-Test", "1") }

        assertEquals(HttpStatusCode.OK, answer.status)
        assertTrue(FakeAgent.CONSOLE_CALLS.any { it.contains("/api/traces?limit=1") }, FakeAgent.CONSOLE_CALLS.toString())
    }
}
