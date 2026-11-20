package dev.tracepanel.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
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
    // A bare connect to the address: an agent on this machine accepts at once.
    private static final int PROBE_TIMEOUT_MS = 250;
    // An agent that exited (idle, killed, a new build) is started again at once; one that couldn't start, after this.
    private static final long RESTART_MS = 30_000;
    private static final String IDLE_EXIT_S = "1800";

    private final Path store;
    private final Map<String, String> env;
    private final AgentDownload download;
    private long failedAt = Long.MIN_VALUE / 2;
    /** The last start found no binary: what the console's page says then. */
    private volatile boolean noBinary;

    public Agent(Path store, Map<String, String> env) {
        this.store = store;
        this.env = env;
        this.download = new AgentDownload(env);
    }

    /**
     * Gets the agent's binary ready in the background while the app starts, so its first request doesn't wait for a
     * download: when no agent runs for the project and none is named.
     */
    public void prepare() {
        String explicit = env.get("TRACEPANEL_AGENT_BIN");
        if (!autoStart() || (explicit != null && !explicit.isBlank()) || !address().isEmpty()) return;
        Thread thread = new Thread(download::binary, "tracepanel-agent-download");
        thread.setDaemon(true);
        thread.start();
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
     * `gone`: the one at its address didn't take the app's hello, so whether it answers at all is asked first.
     * Its addresses stay either way: they may be another process's agent's, starting or busy this very moment, and
     * an agent that didn't exit cleanly leaves its own to the next one, which removes them while it holds the store's
     * lock.
     */
    public synchronized boolean ensure(boolean gone) {
        if (!gone && !address().isEmpty()) return true;
        if (gone && answers(address())) return true;
        if (!autoStart() || System.currentTimeMillis() - failedAt < RESTART_MS) return false;
        boolean started = start();
        if (!started) failedAt = System.currentTimeMillis();
        return started;
    }

    /**
     * What keeps the project's console from answering, as far as this process can tell: what the console's 503 says
     * (Console), in the words the other SDKs use.
     */
    public Console.NoConsole noConsole() {
        String configured = env.get("TRACEPANEL_CONSOLE_URL");
        if (configured != null && !configured.isBlank()) {
            return new Console.NoConsole("The console doesn't answer", "No console answers at " + configured
                    + " (TRACEPANEL_CONSOLE_URL). Start the agent there with --serve, or correct the address.");
        }
        String named = env.get("TRACEPANEL_AGENT");
        if ("off".equals(named)) {
            return new Console.NoConsole("Tracepanel's agent is off", "Tracepanel's agent is off (TRACEPANEL_AGENT=off): traces"
                    + " aren't kept and there is no console. Unset TRACEPANEL_AGENT to have the app start it.");
        }
        if (!autoStart()) {
            return new Console.NoConsole("No console for the named agent", "The agent named in TRACEPANEL_AGENT (" + named
                    + ") serves no console here. Start it with --serve, and set TRACEPANEL_CONSOLE_URL to its console's address.");
        }
        if (answers(address())) {
            // The agent writes its console's address before its own: one that answers without a console was started so.
            return new Console.NoConsole("Tracepanel's agent runs without its console", "The project's agent runs without its"
                    + " console (it was started without --serve). Stop it: the next request starts one with its console. Store: "
                    + store + ".");
        }
        String explicit = env.get("TRACEPANEL_AGENT_BIN");
        if (explicit != null && !explicit.isBlank() && !Files.isRegularFile(Path.of(explicit))) {
            return new Console.NoConsole("No tracepanel-agent to start", "TRACEPANEL_AGENT_BIN names " + explicit
                    + ", which isn't there. Set it to the tracepanel-agent binary's path.");
        }
        if (noBinary) {
            return new Console.NoConsole("No tracepanel-agent to start", "No tracepanel-agent binary to start: set"
                    + " TRACEPANEL_AGENT_BIN to the agent's path, or put tracepanel-agent on the PATH (the SDK downloads its"
                    + " own unless it is offline).");
        }
        return new Console.NoConsole("Tracepanel's agent didn't start", "Tracepanel's agent didn't start: "
                + store.resolve("agent.log") + " says why. It is tried again within 30 seconds.");
    }

    /**
     * A socket connected to the agent's address, `unix:/path` or `tcp:host:port` (a channel for the former); null for
     * an address of neither kind.
     */
    static Closeable connect(String address, int timeoutMs) throws IOException {
        if (address.startsWith("unix:")) {
            SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
            try {
                socket.connect(UnixDomainSocketAddress.of(Path.of(address.substring("unix:".length()))));
            } catch (IOException | RuntimeException e) {
                socket.close();
                throw e;
            }
            return socket;
        }
        if (address.startsWith("tcp:")) {
            String hostPort = address.substring("tcp:".length());
            int at = hostPort.lastIndexOf(':');
            Socket socket = new Socket();
            try {
                socket.connect(
                        new InetSocketAddress(hostPort.substring(0, at).replaceAll("^\\[|]$", ""), Integer.parseInt(hostPort.substring(at + 1))),
                        timeoutMs);
            } catch (IOException | RuntimeException e) {
                socket.close();
                throw e;
            }
            return socket;
        }
        return null;
    }

    /** Whether an agent listens at the address: an address file alone may be a stopped agent's. */
    static boolean answers(String address) {
        try {
            Closeable socket = connect(address, PROBE_TIMEOUT_MS);
            if (socket == null) return false;
            socket.close();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean start() {
        String binary = binary();
        noBinary = binary == null;
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
        // Until its address answers: the file may still be a stopped agent's (a unix socket's is the same path each
        // time). Another process's agent may win the race (one per store): then that one's answers.
        long deadline = System.currentTimeMillis() + START_WAIT_MS;
        while (!answers(address())) {
            if (System.currentTimeMillis() >= deadline) {
                warn("the agent didn't start (see " + store.resolve("agent.log") + ")");
                return false;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /**
     * TRACEPANEL_AGENT_BIN, else the SDK's own version of the agent (from the user's cache, downloaded there the
     * first time), else tracepanel-agent on the PATH (offline, where none was downloaded).
     */
    private String binary() {
        String explicit = env.get("TRACEPANEL_AGENT_BIN");
        if (explicit != null && !explicit.isBlank()) return Files.isRegularFile(Path.of(explicit)) ? explicit : null;
        Path own = download.binary();
        if (own != null) return own.toString();
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
