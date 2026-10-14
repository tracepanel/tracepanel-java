package dev.tracepanel.integrations;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import io.lettuce.core.event.command.CommandBaseEvent;
import io.lettuce.core.event.command.CommandFailedEvent;
import io.lettuce.core.event.command.CommandListener;
import io.lettuce.core.event.command.CommandStartedEvent;
import io.lettuce.core.event.command.CommandSucceededEvent;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis through Lettuce (Spring Data Redis, the cache, sessions, or a RedisClient of the app's own: client.addListener): every command a redis span of the trace that sent
 * it (GET user:1), with its key, the server and the app's line, through Lettuce's own command listener. The agent
 * finds an N+1 of GETs per item and the same command repeated, as with the Python SDK's.
 */
public final class RedisCommands implements CommandListener {
    private static final String PENDING = "tracepanel";

    private record Pending(Trace trace, long start, String name, Map<String, Object> attrs) {}

    private final String host;
    private final int port;
    private final int database;

    /** For a client of the server at host:port, its database. */
    public RedisCommands(String host, int port, int database) {
        this.host = host;
        this.port = port;
        this.database = database;
    }

    @Override
    public void commandStarted(CommandStartedEvent event) {
        Trace trace = Tracepanel.current();
        if (trace == null) return;
        String command = event.getCommand().getType().toString();
        String key = key(event.getCommand().getArgs() == null ? null : event.getCommand().getArgs().getFirstEncodedKey());
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("db.system", "redis");
        attrs.put("db.operation", command);
        attrs.put("redis.key", key);
        attrs.put("redis.alias", database == 0 ? "redis" : "redis/" + database);
        attrs.put("db.namespace", String.valueOf(database));
        attrs.put("server.address", host);
        attrs.put("server.port", port);
        Tracepanel tracepanel = Tracepanel.get();
        attrs.put("code.origin", tracepanel == null ? null : tracepanel.stacks().origin());
        String name = key == null ? command : command + " " + key;
        event.getContext().put(PENDING, new Pending(trace, Trace.nowMicros(), name, attrs));
    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        finish(event, false);
    }

    @Override
    public void commandFailed(CommandFailedEvent event) {
        finish(event, true);
    }

    private static void finish(CommandBaseEvent event, boolean failed) {
        if (!(event.getContext().remove(PENDING) instanceof Pending found)) return;
        found.trace().done("redis", found.name(), found.attrs(), found.start(), failed);
    }

    private static String key(ByteBuffer encoded) {
        if (encoded == null) return null;
        ByteBuffer copy = encoded.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
