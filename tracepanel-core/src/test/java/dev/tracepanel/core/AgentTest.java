package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class AgentTest {
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void an_agent_that_does_not_take_the_hello_yet_keeps_its_addresses(@TempDir Path root) throws Exception {
        Path store = root.resolve(".tracepanel");
        Files.createDirectories(store);
        Path started = root.resolve("started");
        Path binary = script(root, "touch '" + started + "'");
        Map<String, String> env = Map.of(
                "TRACEPANEL_ROOT", root.toString(), "TRACEPANEL_DIR", store.toString(), "TRACEPANEL_AGENT_BIN", binary.toString());
        // Another process's agent, starting: it listens, and answers no hello yet.
        try (ServerSocket starting = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            String address = "tcp:127.0.0.1:" + starting.getLocalPort();
            Files.writeString(store.resolve("agent.addr"), address + "\n");
            Files.writeString(store.resolve("console.addr"), "http://127.0.0.1:1/\n");
            Agent agent = new Agent(store, env);

            assertFalse(new Sender(Config.load(env, "shop"), agent).connected());

            assertEquals(address, agent.address());
            assertEquals("http://127.0.0.1:1/", agent.console());

            assertFalse(Files.exists(started), "no second agent started");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void an_address_nothing_listens_at_is_a_stopped_agents_and_a_new_one_starts(@TempDir Path root) throws Exception {
        Path store = root.resolve(".tracepanel");
        Files.createDirectories(store);
        try (ServerSocket fresh = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            String address = "tcp:127.0.0.1:" + fresh.getLocalPort();
            // The new agent: it writes its address a moment after it starts, in the store it is given (--dir).
            Path binary = script(root, "sleep 0.2; printf '%s\\n' '" + address + "' > \"$2/agent.addr\"");
            Map<String, String> env = Map.of(
                    "TRACEPANEL_ROOT", root.toString(), "TRACEPANEL_DIR", store.toString(), "TRACEPANEL_AGENT_BIN", binary.toString());
            Files.writeString(store.resolve("agent.addr"), "unix:" + root.resolve("gone.sock") + "\n");
            Agent agent = new Agent(store, env);

            assertTrue(agent.ensure(true));

            assertEquals(address, agent.address());
        }
    }

    @Test
    void whether_an_agent_answers_is_a_bare_connect(@TempDir Path root) throws Exception {
        try (ServerSocket listening = new ServerSocket(0, 5, InetAddress.getLoopbackAddress())) {
            assertTrue(Agent.answers("tcp:127.0.0.1:" + listening.getLocalPort()));
        }

        assertFalse(Agent.answers("unix:" + root.resolve("gone.sock")));
        assertFalse(Agent.answers(""));
        assertFalse(Agent.answers("tcp:nonsense"));
    }

    private static Path script(Path root, String body) throws Exception {
        Path binary = root.resolve("tracepanel-agent");
        Files.writeString(binary, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        return binary;
    }
}
