package shop

import dev.tracepanel.core.Jdbc
import dev.tracepanel.ktor.Tracepanel
import dev.tracepanel.ktor.TracepanelClient
import dev.tracepanel.testing.FakeAgent
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.html.respondHtml
import io.ktor.server.plugins.NotFoundException
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
