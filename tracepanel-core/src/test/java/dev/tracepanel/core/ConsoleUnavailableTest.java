package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** The console's answer when no console answers: 503, why and what to do; a page that reloads itself for a browser. */
class ConsoleUnavailableTest {
    private static final String HTML = "text/html,application/xhtml+xml";
    private static final Map<String, String> RETRY = Map.of("Retry-After", "2", "Cache-Control", "no-store");

    @TempDir
    Path root;

    private Path store() {
        return root.resolve(".tracepanel");
    }

    private Tracepanel tracepanel(Map<String, String> more) throws Exception {
        Files.createDirectories(store());
        Map<String, String> env = new HashMap<>(Map.of(
                "TRACEPANEL_ROOT", root.toString(), "TRACEPANEL_DIR", store().toString(),
                "TRACEPANEL_AGENT_BIN", root.resolve("no-such-agent").toString()));
        env.putAll(more);
        return new Tracepanel(Config.load(env, "shop"), List.of(), env);
    }

    private static Console.Answer get(Tracepanel tracepanel, String accept) {
        return Console.forward(tracepanel, "GET", "api/counts", null, null, "127.0.0.1", accept);
    }

    private static String json(String error) {
        return Json.write(Map.of("error", error));
    }

    private static Map<String, Object> answered(Console.Answer answer) {
        return Map.of("status", answer.status(), "type", answer.contentType(), "headers", answer.headers(),
                "body", new String(answer.body(), StandardCharsets.UTF_8));
    }

    private String withoutConsole() {
        return "The project's agent runs without its console (it was started without --serve). Stop it: the next request"
                + " starts one with its console. Store: " + store() + ".";
    }

    @Test
    void an_agent_without_its_console_gets_a_page_that_says_to_stop_it_and_reloads() throws Exception {
        Tracepanel tracepanel = tracepanel(Map.of());
        try (ServerSocket agent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Files.writeString(store().resolve("agent.addr"), "tcp:127.0.0.1:" + agent.getLocalPort() + "\n");

            Console.Answer answer = get(tracepanel, HTML);
            String page = new String(answer.body(), StandardCharsets.UTF_8);

            assertEquals(List.of(503, "text/html; charset=utf-8", RETRY), List.of(answer.status(), answer.contentType(), answer.headers()));
            assertTrue(page.contains("<meta http-equiv=\"refresh\" content=\"2\">"), page);
            assertTrue(page.contains("<h1>Tracepanel&#39;s agent runs without its console</h1>"), page);
            assertTrue(page.contains("<p>" + withoutConsole().replace("'", "&#39;") + "</p>"), page);
        }
    }

    @Test
    void an_agent_without_its_console_its_console_address_left_over_gets_json() throws Exception {
        Tracepanel tracepanel = tracepanel(Map.of());
        try (ServerSocket agent = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            Files.writeString(store().resolve("agent.addr"), "tcp:127.0.0.1:" + agent.getLocalPort() + "\n");
            Files.writeString(store().resolve("console.addr"), "http://127.0.0.1:9/\n");

            assertEquals(
                    Map.of("status", 503, "type", "application/json", "headers", RETRY, "body", json(withoutConsole())),
                    answered(get(tracepanel, "*/*")));
        }
    }

    @Test
    void the_agent_off_says_so() throws Exception {
        Tracepanel tracepanel = tracepanel(Map.of("TRACEPANEL_AGENT", "off"));

        assertEquals(
                Map.of("status", 503, "type", "application/json", "headers", RETRY, "body", json(
                        "Tracepanel's agent is off (TRACEPANEL_AGENT=off): traces aren't kept and there is no console."
                                + " Unset TRACEPANEL_AGENT to have the app start it.")),
                answered(get(tracepanel, null)));
    }

    @Test
    void no_binary_to_start_says_how_to_get_one() throws Exception {
        Tracepanel tracepanel = tracepanel(Map.of());

        assertEquals(
                Map.of("status", 503, "type", "application/json", "headers", RETRY, "body", json(
                        "TRACEPANEL_AGENT_BIN names " + root.resolve("no-such-agent")
                                + ", which isn't there. Set it to the tracepanel-agent binary's path.")),
                answered(get(tracepanel, null)));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void an_agent_that_does_not_start_points_to_its_log() throws Exception {
        Path binary = root.resolve("tracepanel-agent");
        Files.writeString(binary, "#!/bin/sh\nexit 1\n");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        Tracepanel tracepanel = tracepanel(Map.of("TRACEPANEL_AGENT_BIN", binary.toString()));

        assertEquals(
                Map.of("status", 503, "type", "application/json", "headers", RETRY, "body", json(
                        "Tracepanel's agent didn't start: " + store().resolve("agent.log")
                                + " says why. It is tried again within 30 seconds.")),
                answered(get(tracepanel, null)));
    }

    @Test
    void the_page_needs_nothing_from_elsewhere_and_what_it_says_makes_no_markup() {
        String page = Console.page(new Console.NoConsole("A <b>title</b>", "Store: /tmp/\"x\" & 'y'."));

        assertTrue(page.contains("<title>Tracepanel: A &#60;b&#62;title&#60;/b&#62;</title>"), page);
        assertTrue(page.contains("<p>Store: /tmp/&#34;x&#34; &#38; &#39;y&#39;.</p>"), page);

        assertFalse(Pattern.compile("<(script|link|img)\\b|https?:").matcher(page).find(), page);
    }
}
