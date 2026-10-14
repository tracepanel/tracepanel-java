package dev.tracepanel.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The project's agent: where it listens and serves its console (it writes both in the store), started when none
 * answers, as the other SDKs start it, in a session of its own so the app's Ctrl-C and restarts leave it running.
 */
public final class Agent {
    private static final long START_WAIT_MS = 1500;
    // An agent that exited (idle, killed, a new build) is started again at once; one that couldn't start, after this.
    private static final long RESTART_MS = 30_000;
    private static final String IDLE_EXIT_S = "1800";

    private final Path store;
    private final Map<String, String> env;
    private long failedAt = Long.MIN_VALUE / 2;

    public Agent(Path store, Map<String, String> env) {
        this.store = store;
        this.env = env;
    }

    /** The agent's address: TRACEPANEL_AGENT (an agent elsewhere), or the one the project's agent wrote. */
    public String address() {
        String named = env.get("TRACEPANEL_AGENT");
        if (named != null && !named.isBlank() && !named.equals("auto")) return named;
        return read("agent.addr");
    }

    /** Where its console answers: http://127.0.0.1:port/, or null. */
    public String console() {
        String named = env.get("TRACEPANEL_CONSOLE_URL");
        if (named != null && !named.isBlank()) return named.endsWith("/") ? named : named + "/";
        String found = read("console.addr");
        return found.isEmpty() ? null : found;
    }

    private boolean autoStart() {
        String named = env.get("TRACEPANEL_AGENT");
        return named == null || named.isBlank() || named.equals("auto");
    }

    /**
     * Whether an agent runs for the project: one already does, or this starts it (RESTART_MS after a failure).
     * `gone`: the one at its address didn't answer, and its addresses go before a new one starts.
     */
    public synchronized boolean ensure(boolean gone) {
        if (!gone && !address().isEmpty()) return true;
        if (!autoStart() || System.currentTimeMillis() - failedAt < RESTART_MS) return false;
        if (gone) {
            for (String name : List.of("agent.addr", "console.addr")) {
                try {
                    Files.deleteIfExists(store.resolve(name));
                } catch (IOException ignored) {
                    // Another process's: it starts the new one.
                }
            }
        }
        boolean started = start();
        if (!started) failedAt = System.currentTimeMillis();
        return started;
    }

    private boolean start() {
        String binary = binary();
        if (binary == null) {
            warn("no tracepanel-agent: set TRACEPANEL_AGENT_BIN, or put it on the PATH");
            return false;
        }
        try {
            Files.createDirectories(store);
            List<String> command = new ArrayList<>();
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (!windows) {
                // Ctrl-C reaches the whole process group: the agent ignores it, and goes on its own (idle) or by TERM.
                command.addAll(List.of("/bin/sh", "-c", "trap '' INT HUP; exec \"$0\" \"$@\""));
            }
            command.addAll(List.of(binary, "--dir", store.toString(), "--serve", "127.0.0.1:0", "--idle-exit", IDLE_EXIT_S));
            File log = store.resolve("agent.log").toFile();
            new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(new File(windows ? "NUL" : "/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            warn("the agent didn't start: " + e.getMessage());
            return false;
        }
        long deadline = System.currentTimeMillis() + START_WAIT_MS;
        while (System.currentTimeMillis() < deadline && address().isEmpty()) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (address().isEmpty()) warn("the agent didn't start (see " + store.resolve("agent.log") + ")");
        return !address().isEmpty();
    }

    /** TRACEPANEL_AGENT_BIN, else tracepanel-agent on the PATH. */
    private String binary() {
        String explicit = env.get("TRACEPANEL_AGENT_BIN");
        if (explicit != null && !explicit.isBlank()) return Files.isRegularFile(Path.of(explicit)) ? explicit : null;
        String path = env.getOrDefault("PATH", "");
        for (String dir : path.split(File.pathSeparator)) {
            for (String name : List.of("tracepanel-agent", "tracepanel-agent.exe")) {
                Path candidate = Path.of(dir, name);
                if (Files.isExecutable(candidate)) return candidate.toString();
            }
        }
        return null;
    }

    private String read(String name) {
        try {
            return Files.readString(store.resolve(name), StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return "";
        }
    }

    static void warn(String message) {
        System.err.println("tracepanel ▸ " + message);
    }
}
