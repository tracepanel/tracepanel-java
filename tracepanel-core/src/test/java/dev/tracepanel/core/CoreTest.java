package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreTest {
    @Test
    void ids_are_ulids_in_time_order() throws InterruptedException {
        String first = Ulid.next();
        Thread.sleep(2);
        String second = Ulid.next();

        assertTrue(first.matches("[0-9A-HJKMNP-TV-Z]{26}"), first);
        assertTrue(first.substring(0, 10).compareTo(second.substring(0, 10)) < 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_trace_keeps_its_first_spans_and_records_and_counts_the_rest() {
        Trace trace = new Trace("http", "GET /", Map.of());
        for (int n = 0; n < Trace.MAX_SPANS + 10; n++) trace.end(trace.begin("function", "step " + n, Map.of()), false);
        for (int n = 0; n < Trace.MAX_LOGS + 5; n++) trace.log("info", "shop", "line " + n, null, Map.of());

        Map<String, Object> doc = trace.finish();
        Map<String, Object> attrs = (Map<String, Object>) doc.get("attrs");

        assertEquals(Trace.MAX_SPANS, ((List<?>) doc.get("spans")).size());
        assertEquals(Trace.MAX_LOGS, ((List<?>) doc.get("logs")).size());
        // The root is the first span: it stays, and the cut takes the latest ones.
        assertEquals(11L, attrs.get("tracepanel.dropped_spans"));
        assertEquals(5L, attrs.get("tracepanel.dropped_logs"));
    }

    @Test
    void json_escapes_what_a_string_holds() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("message", "a \"quote\"\nand a tab\t\u0001");
        doc.put("count", 3);
        doc.put("ratio", 0.5);
        doc.put("none", null);
        doc.put("list", List.of(true, "x"));

        assertEquals("{\"message\":\"a \\\"quote\\\"\\nand a tab\\t\\u0001\",\"count\":3,\"ratio\":0.5,\"none\":null,\"list\":[true,\"x\"]}",
                Json.write(doc));
    }

    @Test
    void settings_come_from_the_same_variables_as_the_other_sdks() {
        Config config = Config.load(Map.of(
                "TRACEPANEL_ROOT", "/srv/shop",
                "TRACEPANEL_SLOW_SQL_MS", "50",
                "TRACEPANEL_ENABLED", "off"), "shop");

        assertEquals(false, config.enabled());
        assertEquals(Path.of("/srv/shop/.tracepanel"), config.storeDir());
        assertEquals("shop", config.service());
        assertEquals(50L, config.thresholds().get("slow_sql_ms"));
        assertEquals(3, config.thresholds().get("n_plus_one"));
    }

    @Test
    void a_request_s_details_are_kept_and_profiling_is_possible_unless_the_settings_say_otherwise() {
        Config defaults = Config.load(Map.of(), "shop");
        Config set = Config.load(Map.of(
                "TRACEPANEL_CAPTURE_REQUEST", "0",
                "TRACEPANEL_PROFILE", "1",
                "TRACEPANEL_PROFILE_ENABLED", "off",
                "TRACEPANEL_PROFILE_INTERVAL_MS", "5"), "shop");

        assertEquals(List.of(true, false, true, 1), List.of(
                defaults.captureRequest(), defaults.profile(), defaults.profileEnabled(), defaults.profileIntervalMs()));
        assertEquals(List.of(false, true, false, 5), List.of(
                set.captureRequest(), set.profile(), set.profileEnabled(), set.profileIntervalMs()));
    }

    @Test
    void the_app_s_code_is_its_own_packages_not_the_libraries() {
        Stacks stacks = new Stacks(Path.of("/srv/shop"), List.of("com.shop"));

        assertTrue(stacks.inApp("com.shop.BookService"));
        assertTrue(!stacks.inApp("com.shopping.Other"));
        assertTrue(!stacks.inApp("org.springframework.web.Servlet"));
        // Made at runtime in the app's package: a lazy relation's proxy, a Spring subclass.
        assertTrue(!stacks.inApp("com.shop.Author$HibernateProxy$Xyz"));
        assertTrue(!stacks.inApp("com.shop.BookService$$SpringCGLIB$$0"));
        assertEquals("com/shop/BookService.java:42 in list", stacks.where("com.shop.BookService", "BookService.java", 42, "list"));
    }

    @Test
    void a_redirect_that_answers_a_page_s_navigation_is_named_in_a_cookie_after_those_before_it() {
        String a = "01K7B2Y7Q8M3E5R9T1V4W6X8Z0";
        String b = "01K7B2Y7Q8M3E5R9T1V4W6X8Z1";
        String seven = String.join("%2C", "1", "2", "3", "4", "5", "6", "7");

        assertEquals("tracepanel_redirect=" + b + "; Max-Age=30; Path=/; SameSite=Lax",
                Widget.redirectCookie(b, 302, "navigate", null, "theme=dark"));
        assertEquals("tracepanel_redirect=" + a + "%2C" + b + "; Max-Age=30; Path=/; SameSite=Lax",
                Widget.redirectCookie(b, 303, null, "text/html,application/xhtml+xml", "tracepanel_redirect=" + a + "; theme=dark"));
        assertEquals("tracepanel_redirect=" + String.join("%2C", "4", "5", "6", "7", b) + "; Max-Age=30; Path=/; SameSite=Lax",
                Widget.redirectCookie(b, 302, "navigate", null, "tracepanel_redirect=" + seven));
        assertEquals("tracepanel_redirect=" + b + "; Max-Age=30; Path=/; SameSite=Lax",
                Widget.redirectCookie(b, 302, "navigate", null, "tracepanel_redirect=%zz"));

        assertEquals(null, Widget.redirectCookie(b, 302, "cors", "text/html", null));
        assertEquals(null, Widget.redirectCookie(b, 302, null, "application/json", null));
        assertEquals(null, Widget.redirectCookie(b, 200, "navigate", null, null));
    }

    @Test
    void a_query_s_library_caller_is_past_the_orm_the_pool_and_the_driver() {
        assertTrue(Stacks.queryLayer("org.hibernate.collection.spi.AbstractPersistentCollection"));
        assertTrue(Stacks.queryLayer("org.springframework.data.repository.core.support.RepositoryMethodInvoker"));
        assertTrue(Stacks.queryLayer("org.springframework.jdbc.core.JdbcTemplate"));
        assertTrue(Stacks.queryLayer("com.zaxxer.hikari.pool.ProxyPreparedStatement"));
        assertTrue(Stacks.queryLayer("org.postgresql.jdbc.PgPreparedStatement"));
        assertTrue(Stacks.queryLayer("jdk.proxy2.$Proxy91"));

        assertTrue(!Stacks.queryLayer("com.fasterxml.jackson.databind.ser.std.CollectionSerializer"));
        assertTrue(!Stacks.queryLayer("org.springframework.security.authentication.dao.DaoAuthenticationProvider"));
        assertTrue(!Stacks.queryLayer("org.springframework.data.rest.webmvc.RepositoryEntityController"));
        assertTrue(!Stacks.queryLayer("org.thymeleaf.spring6.expression.SPELVariableExpressionEvaluator"));
    }

    @Test
    void a_traceparent_is_written_from_the_ulids_as_the_python_sdk_writes_it() {
        assertEquals("01a13b4a0cae6df75a2aef8e76021409", W3c.traceHex("01M4XMM35EDQVNMAQFHSV04509"));
        assertEquals("5a2aef8e7602140a", W3c.spanId("01M4XMM35EDQVNMAQFHSV0450A"));
    }

    @Test
    void the_platform_is_named_as_the_agents_npm_packages_are() {
        assertEquals("darwin-arm64", AgentDownload.platform("Mac OS X", "aarch64", false));
        assertEquals("darwin-x64", AgentDownload.platform("Mac OS X", "x86_64", false));
        assertEquals("linux-x64-gnu", AgentDownload.platform("Linux", "amd64", false));
        assertEquals("linux-arm64-musl", AgentDownload.platform("Linux", "aarch64", true));
        assertEquals("linux-ppc64-gnu", AgentDownload.platform("Linux", "ppc64le", false));
        assertEquals("linux-arm", AgentDownload.platform("Linux", "arm", false));
        assertEquals("win32-x64", AgentDownload.platform("Windows 11", "amd64", false));
        assertEquals(null, AgentDownload.platform("FreeBSD", "amd64", false));
        assertEquals(null, AgentDownload.platform("Linux", "s390x", true));
    }

    @Test
    void the_widgets_attributes_read_the_same_in_any_encoding() {
        String page = Widget.decorate("<html><head></head></html>", new Trace("http", "GET /", java.util.Map.of()), "GET", 200,
                "/__tracepanel__/", "Java 21 \u00b7 Spring Boot 4.1");

        assertTrue(page.contains("data-tracepanel-runtime=\"Java 21 &#183; Spring Boot 4.1\""), page);
        assertTrue(page.chars().allMatch(c -> c < 128), page);
    }

    @Test
    void a_trace_that_ended_takes_nothing_more() {
        Trace trace = new Trace("http", "GET /", java.util.Map.of());
        java.util.Map<String, Object> doc = trace.finish();

        trace.error(new IllegalStateException("late"), new Stacks(java.nio.file.Path.of("."), java.util.List.of()), false);
        trace.done("sql", "SELECT", java.util.Map.of(), Trace.nowMicros(), false);
        trace.attr("late", true);

        assertEquals(java.util.List.of(), doc.get("errors"));
        assertEquals(1, ((java.util.List<?>) doc.get("spans")).size());
        assertEquals(null, ((java.util.Map<?, ?>) doc.get("attrs")).get("late"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_frame_past_the_end_of_its_file_has_no_source_and_the_error_is_recorded(@org.junit.jupiter.api.io.TempDir Path root)
            throws java.io.IOException {
        Path source = root.resolve("src/main/kotlin/com/shop/Prices.kt");
        java.nio.file.Files.createDirectories(source.getParent());
        java.nio.file.Files.writeString(source, "package com.shop\n\nfun price() = 1\n");
        // A Kotlin inline function's frame: a line number past the file's end, where the inlined body is mapped.
        IllegalStateException error = new IllegalStateException("no price");
        error.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.shop.PricesKt", "price", "Prices.kt", 233),
                new StackTraceElement("com.shop.PricesKt", "main", "Prices.kt", 3),
        });
        Trace trace = new Trace("http", "GET /", Map.of());

        trace.error(error, new Stacks(root, List.of("com.shop")), false);
        Map<String, Object> recorded = ((List<Map<String, Object>>) trace.finish().get("errors")).get(0);
        List<Map<String, Object>> frames = (List<Map<String, Object>>) recorded.get("frames");

        assertEquals("no price", recorded.get("message"));
        assertEquals(Map.of("filename", "src/main/kotlin/com/shop/Prices.kt", "module", "com.shop.PricesKt", "function", "price",
                "lineno", 233, "in_app", true, "context", List.of()), withoutNulls(frames.get(1)));
        assertEquals(null, frames.get(1).get("context_start"));
        assertEquals(List.of("package com.shop", "", "fun price() = 1"), frames.get(0).get("context"));
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> frame) {
        Map<String, Object> out = new LinkedHashMap<>(frame);
        out.values().removeIf(java.util.Objects::isNull);
        return out;
    }

    @Test
    @SuppressWarnings("unchecked")
    void a_call_s_span_names_its_port_when_it_isn_t_the_scheme_s() {
        Trace trace = new Trace("http", "GET /", Map.of());
        Trace before = Tracepanel.bind(trace);
        try {
            for (String url : List.of("http://127.0.0.1:8001/rates", "http://api.example.com:80/rates", "https://api.example.com/rates",
                    "https://api.example.com:8443/rates")) {
                HttpCalls.start("get", java.net.URI.create(url)).end(200);
            }
        } finally {
            Tracepanel.bind(before);
        }

        List<Map<String, Object>> spans = (List<Map<String, Object>>) trace.finish().get("spans");

        assertEquals(List.of("GET 127.0.0.1:8001/rates", "GET api.example.com/rates", "GET api.example.com/rates",
                "GET api.example.com:8443/rates"), spans.stream().skip(1).map(span -> span.get("name")).toList());
    }
}
