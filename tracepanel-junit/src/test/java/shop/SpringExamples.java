package shop;

import static dev.tracepanel.junit.TracepanelExtension.maxQueries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.tracepanel.junit.TestTrace;
import dev.tracepanel.junit.Tracepanel;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;

/** Tests of a Spring Boot app the extension runs, through the JUnit Platform's test kit. */
final class SpringExamples {
    private SpringExamples() {}

    @Tag("example")
    @Tracepanel
    @SpringBootTest
    @AutoConfigureMockMvc
    static class WithMockMvc {
        @Autowired
        MockMvc mvc;

        @Test
        void books(TestTrace trace) throws Exception {
            mvc.perform(get("/books")).andExpect(status().isOk());
            assertEquals(5, trace.queries());
            Library.authors();
            assertEquals(6, trace.queries());
            maxQueries(5, () -> mvc.perform(get("/books")));
        }
    }

    @Tag("example")
    @Tracepanel
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = "server.port=8261")
    @AutoConfigureTestRestTemplate
    @AutoConfigureWebTestClient
    static class OverHttp {
        @Autowired
        TestRestTemplate rest;

        @Autowired
        WebTestClient client;

        @Test
        void testRestTemplate(TestTrace trace) {
            assertEquals(200, rest.getForEntity("/books", String.class).getStatusCode().value());
            assertEquals(5, trace.queries());
        }

        @Test
        void webTestClient(TestTrace trace) {
            client.get().uri("/books").exchange().expectStatus().isOk();
            assertEquals(5, trace.queries());
        }
    }

    @Tag("example")
    @Tracepanel
    @WebMvcTest(Hello.class)
    static class Slice {
        @Autowired
        MockMvc mvc;

        @Test
        void page() throws Exception {
            mvc.perform(get("/hello")).andExpect(content().string("<html><head><title>Hello</title></head><body>Hello</body></html>"));
        }
    }

    @Tag("example")
    @Tracepanel
    @JdbcTest
    static class Jdbc {
        @Autowired
        JdbcTemplate db;

        @Test
        @Tracepanel(maxQueries = 1)
        void twoQueries() {
            db.queryForList("SELECT name FROM author ORDER BY id", String.class);
            db.queryForList("SELECT title FROM book ORDER BY id", String.class);
        }
    }
}
