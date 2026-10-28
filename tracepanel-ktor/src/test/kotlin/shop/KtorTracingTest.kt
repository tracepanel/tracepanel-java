package shop

import dev.tracepanel.core.Jdbc
import dev.tracepanel.ktor.Tracepanel
import dev.tracepanel.ktor.TracepanelClient
import dev.tracepanel.ktor.tracepanelUser
import dev.tracepanel.testing.FakeAgent
import dev.tracepanel.core.Secrets
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.contentType
import io.ktor.server.request.receiveText
import io.ktor.server.routing.post
import io.ktor.server.response.respondRedirect
import io.ktor.server.sessions.SessionSerializer
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import lib.countBooks
import org.hibernate.loader.loadBook
import org.jetbrains.exposed.dao.findBook
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

/** Busy for about `millis`: where a profile sees the time go. */
fun crunch(millis: Long): Long {
    val until = System.nanoTime() + millis * 1_000_000
    var sum = 0L
    while (System.nanoTime() < until) sum += java.lang.Long.numberOfTrailingZeros(System.nanoTime())
    return sum % 10
}

/** The app's own exceptions, which StatusPages answers. */
class OutOfStock(message: String) : RuntimeException(message)

class PriceMissing(message: String) : RuntimeException(message)

/** The app's session, kept by the Sessions plugin in a cookie. */
data class Cart(val items: String, val csrfToken: String)

private object CartSerializer : SessionSerializer<Cart> {
    override fun serialize(session: Cart) = "${session.items}|${session.csrfToken}"

    override fun deserialize(text: String) = text.split('|').let { Cart(it[0], it[1]) }
}

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
            install(Sessions) { cookie<Cart>("cart") { serializer = CartSerializer } }
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
                post("/orders") {
                    val order = call.receiveText()
                    call.response.header("X-Order", "17")
                    call.respondText("ordered ${order.length}")
                }
                post("/ignored") { call.respondText("ignored") }
                post("/shelves") {
                    title(1)
                    call.respondRedirect("/page")
                }
                get("/shelves/count") { call.respondText(countBooks(db).toString()) }
                get("/orm/hibernate") { call.respondText(loadBook(db, 1)) }
                get("/orm/exposed") { call.respondText(findBook(db, 2)) }
                get("/cart") {
                    call.sessions.set(Cart("3 books", "s3cret"))
                    call.respondText("added")
                }
                get("/report") {
                    // On another thread, as a coroutine goes: the profile follows the call there.
                    val total = withContext(Dispatchers.Default) { crunch(60) }
                    call.respondText("report $total")
                }
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
        // A lambda has no name of its own: no code.function, so the console has no longer name to show on hover.
        assertEquals(emptyMap<String, Any>(), view["attrs"])
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
    fun a_call_s_headers_query_cookies_body_and_answer_are_recorded_secrets_masked() = app {
        client.post("/orders?coupon=KTOR&token=t") {
            header("X-Api-Key", "k1")
            header(HttpHeaders.Cookie, "theme=dark; session_id=abc")
            contentType(ContentType.Application.Json)
            setBody("{\"items\":[\"a\"],\"password\":\"p\"}")
        }
        client.post("/ignored") {
            contentType(ContentType.Text.Plain)
            setBody("never read")
        }
        val orders = attrs(FakeAgent.traceAt("/orders"))
        val ignored = attrs(FakeAgent.traceAt("/ignored"))

        assertEquals("application/json", orders["http.request.header.content_type"])
        assertEquals(Secrets.MASK, orders["http.request.header.x_api_key"])
        assertEquals("coupon=KTOR&token=${Secrets.MASK}", orders["url.query"])
        assertEquals("dark", orders["http.request.cookie.theme"])
        assertEquals(Secrets.MASK, orders["http.request.cookie.session_id"])
        assertEquals(null, orders["http.request.header.cookie"])

        assertEquals("{\"items\":[\"a\"],\"password\":\"${Secrets.MASK}\"}", orders["http.request.body"])
        assertEquals("17", orders["http.response.header.x_order"])

        assertEquals(null, ignored["http.request.body"])
    }

    /** The trace of the call that sent X-Test: `test`, once the SDK sent it. */
    private fun traceOf(test: String): Map<String, Any> {
        repeat(250) {
            FakeAgent.TRACES.firstOrNull { attrs(it)["http.request.header.x_test"] == test }?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no trace of $test")
    }

    @Test
    fun a_form_s_post_that_redirects_a_page_s_navigation_names_itself_in_the_redirect_cookie() = app {
        val noRedirects = createClient { followRedirects = false }
        val sent = noRedirects.post("/shelves") {
            header("Sec-Fetch-Mode", "navigate")
            header("X-Test", "redirect-1")
        }
        val again = noRedirects.post("/shelves") {
            header(HttpHeaders.Accept, "text/html,application/xhtml+xml")
            header(HttpHeaders.Cookie, "tracepanel_redirect=01K7B2Y7Q8M3E5R9T1V4W6X8Z0")
            header("X-Test", "redirect-2")
        }
        val fetched = noRedirects.post("/shelves") {
            header("Sec-Fetch-Mode", "cors")
            header("X-Test", "redirect-3")
        }
        val first = traceOf("redirect-1")["id"]
        val second = traceOf("redirect-2")["id"]

        assertEquals(HttpStatusCode.Found, sent.status)
        assertEquals(listOf("tracepanel_redirect=$first; Max-Age=30; Path=/; SameSite=Lax"), sent.headers.getAll(HttpHeaders.SetCookie))
        assertEquals(first, sent.headers["Tracepanel-Id"])

        assertEquals(
            listOf("tracepanel_redirect=01K7B2Y7Q8M3E5R9T1V4W6X8Z0%2C$second; Max-Age=30; Path=/; SameSite=Lax"),
            again.headers.getAll(HttpHeaders.SetCookie),
        )

        assertEquals(HttpStatusCode.Found, fetched.status)
        assertEquals(null, fetched.headers.getAll(HttpHeaders.SetCookie))
    }

    @Test
    fun a_query_only_library_code_made_names_the_library_code_that_asked_for_it() = app {
        assertEquals("3", client.get("/shelves/count").bodyAsText())
        val count = spans(FakeAgent.traceAt("/shelves/count"), "sql").single()["attrs"] as Map<*, *>

        assertEquals(null, count["code.origin"])
        assertTrue(Regex("""lib/Shelf\.kt:\d+ in invokeSuspend""").matches(count["code.caller"].toString()), count.toString())
    }

    @Test
    fun a_query_names_the_orm_that_made_it_or_jdbc() = app {
        assertEquals("One", client.get("/orm/hibernate").bodyAsText())
        assertEquals("Two", client.get("/orm/exposed").bodyAsText())
        client.get("/books/3")
        val db = { path: String -> (spans(FakeAgent.traceAt(path), "sql").first()["attrs"] as Map<*, *>).filterKeys { it.toString().startsWith("db.") && it != "db.response.returned_rows" } }

        assertEquals<Map<*, *>>(
            mapOf(
                "db.system" to "h2", "db.vendor" to "h2", "db.client" to "hibernate", "db.alias" to "default",
                "db.namespace" to "BOOKS", "db.statement" to "select b1_0.title from book b1_0 where b1_0.id=?",
                "db.fingerprint" to "select b1_0.title from book b1_0 where b1_0.id=?", "db.params" to listOf(1),
                "db.params_hash" to db("/orm/hibernate")["db.params_hash"], "db.many" to false,
            ),
            db("/orm/hibernate"),
        )
        assertEquals("exposed", db("/orm/exposed")["db.client"])
        assertEquals("jdbc", db("/books/3")["db.client"])
    }

    @Test
    fun a_query_the_app_s_code_made_has_no_library_caller() = app {
        client.get("/books/2")
        val title = spans(FakeAgent.traceAt("/books/2"), "sql").first()["attrs"] as Map<*, *>

        assertTrue(title["code.origin"].toString().contains("KtorTracingTest.kt"), title.toString())
        assertEquals(false, title.containsKey("code.caller"))
    }

    @Test
    fun the_session_the_call_left_is_recorded_its_secrets_masked() = app {
        client.get("/cart")
        client.get("/me")
        val cart = attrs(FakeAgent.traceAt("/cart"))
        val me = attrs(FakeAgent.traceAt("/me"))

        assertEquals("'3 books'", cart["session.items"])
        assertEquals(Secrets.MASK, cart["session.csrftoken"])

        assertEquals(emptyList<String>(), me.keys.filter { it.startsWith("session.") })
    }

    @Test
    fun a_call_that_asks_is_profiled_on_the_threads_its_coroutines_run_on() = app {
        client.get("/report") { header("X-Tracepanel-Profile", "1") }
        @Suppress("UNCHECKED_CAST")
        val profile = FakeAgent.traceAt("/report")["profile"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val frames = profile["frames"] as List<Map<String, Any>>
        val crunch = frames.single { it["function"] == "KtorTracingTestKt.crunch" }
        // The tests' Kotlin is under no source root the stacks look in: its file as its package names it.

        assertEquals(1000, profile["interval_us"])
        assertTrue((profile["samples"] as Int) > 5, profile.toString())
        assertEquals(mapOf("module" to "shop.KtorTracingTestKt", "file" to "shop/KtorTracingTest.kt", "in_app" to true),
            crunch.filterKeys { it in setOf("module", "file", "in_app") })
    }

    @Test
    fun a_call_that_doesn_t_ask_is_not_profiled() = app {
        client.get("/books/3")

        assertEquals(null, FakeAgent.traceAt("/books/3")["profile"])
    }

    @Test
    fun the_console_answers_under_the_prefix() = app {
        val answer = client.get("/__tracepanel__/api/traces?limit=1") { header("X-Test", "1") }

        assertEquals(HttpStatusCode.OK, answer.status)
        assertTrue(FakeAgent.CONSOLE_CALLS.any { it.contains("/api/traces?limit=1") }, FakeAgent.CONSOLE_CALLS.toString())
    }
}
