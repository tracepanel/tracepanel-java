package shop

import dev.tracepanel.junit.TracepanelExtension.maxQueries
import dev.tracepanel.junit.TracepanelExtension.testTrace
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import dev.tracepanel.junit.Tracepanel as TracepanelTest
import dev.tracepanel.ktor.Tracepanel as TracepanelPlugin

/** A Ktor app's test the extension runs, through the JUnit Platform's test kit. */
@Tag("example")
@TracepanelTest
class KtorExamples {
    @Test
    fun books() {
        testApplication {
            application {
                install(TracepanelPlugin)
                routing {
                    get("/books") { call.respondText(Library.titlesWithAuthors().joinToString()) }
                    get("/hello") { call.respondText("<html><head></head><body>Hello</body></html>", ContentType.Text.Html) }
                }
            }
            val response = client.get("/books")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(5, testTrace().queries())
            assertEquals("<html><head></head><body>Hello</body></html>", client.get("/hello").bodyAsText())
        }
        maxQueries(1) { Library.authors() }
    }
}
