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
}
