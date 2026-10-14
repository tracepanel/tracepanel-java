package dev.tracepanel.spring;

import com.mongodb.MongoClientSettings;
import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import dev.tracepanel.core.Json;
import dev.tracepanel.core.Sql;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * MongoDB: every command the app sends (Spring Data MongoDB, MongoTemplate, the driver) a mongodb span, through the
 * driver's own command monitoring. A span is the command (find books), its database, the server and the app's line
 * that sent it. As with SQL, the command's shape is its db.statement ({"find": "books", "filter": {"author": "?"}}),
 * its values db.params and db.params_hash: the agent tells an N+1 (a find per item) from duplicates by them. The
 * Python SDK writes the same.
 */
final class MongoCommands implements CommandListener {
    // What a driver sends for itself, not for the app.
    private static final Set<String> OWN = Set.of("hello", "ismaster", "ping", "saslstart", "saslcontinue",
            "authenticate", "getnonce", "endsessions", "buildinfo", "killcursors", "getlasterror");
    // Fields of a command that are the driver's, not the query's.
    private static final Set<String> NOISE = Set.of(
            "lsid", "$clusterTime", "$db", "txnNumber", "$readPreference", "autocommit", "startTransaction", "signature");
    // Where a command carries the app's values: shown as ?, the rest (the collection, limit, sort) as sent.
    private static final Set<String> VALUES =
            Set.of("filter", "query", "documents", "updates", "deletes", "pipeline", "q", "u", "update");
    private static final int MAX_STATEMENT = 2000;
    private static final int MAX_PENDING = 1000;

    private record Pending(Trace trace, long start, String name, Map<String, Object> attrs) {}

    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    @Override
    public void commandStarted(CommandStartedEvent event) {
        String name = event.getCommandName();
        Trace trace = Tracepanel.current();
        if (trace == null || OWN.contains(name.toLowerCase(Locale.ROOT)) || pending.size() >= MAX_PENDING) return;
        BsonDocument command = event.getCommand();
        List<Object> values = new ArrayList<>();
        Map<String, Object> shaped = new LinkedHashMap<>();
        for (Map.Entry<String, BsonValue> field : command.entrySet()) {
            if (NOISE.contains(field.getKey())) continue;
            shaped.put(field.getKey(), VALUES.contains(field.getKey()) ? shape(field.getValue(), values) : plain(field.getValue()));
        }
        String statement = Json.write(shaped);
        if (statement.length() > MAX_STATEMENT) statement = statement.substring(0, MAX_STATEMENT - 1) + "…";
        String collection = collection(name, command);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("db.system", "mongodb");
        attrs.put("db.operation", name);
        attrs.put("db.namespace", event.getDatabaseName());
        attrs.put("db.statement", statement);
        attrs.put("db.params", values.stream().map(String::valueOf).toList());
        attrs.put("db.params_hash", Sql.hash(Json.write(values.stream().map(String::valueOf).toList())));
        if (collection != null) attrs.put("db.collection.name", collection);
        if (event.getConnectionDescription() != null) {
            attrs.put("server.address", event.getConnectionDescription().getServerAddress().getHost());
            attrs.put("server.port", event.getConnectionDescription().getServerAddress().getPort());
        }
        Tracepanel tracepanel = Tracepanel.get();
        attrs.put("code.origin", tracepanel == null ? null : tracepanel.stacks().origin());
        String title = collection != null ? name + " " + collection : name;
        pending.put((long) event.getRequestId(), new Pending(trace, Trace.nowMicros(), title, attrs));
    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        Pending found = pending.remove((long) event.getRequestId());
        if (found == null) return;
        Integer rows = rows(event.getResponse());
        if (rows != null) found.attrs().put("db.response.returned_rows", rows);
        found.trace().done("mongodb", found.name(), found.attrs(), found.start(), false);
    }

    @Override
    public void commandFailed(CommandFailedEvent event) {
        Pending found = pending.remove((long) event.getRequestId());
        if (found == null) return;
        found.attrs().put("error.type", event.getThrowable().getClass().getSimpleName());
        found.trace().done("mongodb", found.name(), found.attrs(), found.start(), true);
    }

    /** A value with the app's values as ? (collected in order): a list of plain values is one ?, as SQL's IN. */
    private static Object shape(BsonValue value, List<Object> values) {
        if (value instanceof BsonDocument document) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, BsonValue> field : document.entrySet()) out.put(field.getKey(), shape(field.getValue(), values));
            return out;
        }
        if (value instanceof BsonArray array) {
            if (array.stream().noneMatch(item -> item.isDocument() || item.isArray())) {
                array.forEach(item -> values.add(plain(item)));
                return List.of("?");
            }
            return array.stream().map(item -> shape(item, values)).toList();
        }
        values.add(plain(value));
        return "?";
    }

    /** A BSON value as JSON writes it: documents and arrays as such, numbers and text as themselves. */
    private static Object plain(BsonValue value) {
        if (value == null || value.isNull()) return null;
        if (value.isString()) return value.asString().getValue();
        if (value.isInt32()) return value.asInt32().getValue();
        if (value.isInt64()) return value.asInt64().getValue();
        if (value.isDouble()) return value.asDouble().getValue();
        if (value.isBoolean()) return value.asBoolean().getValue();
        if (value instanceof BsonDocument document) {
            Map<String, Object> out = new LinkedHashMap<>();
            document.forEach((key, item) -> out.put(key, plain(item)));
            return out;
        }
        if (value instanceof BsonArray array) return array.stream().map(MongoCommands::plain).toList();
        return value.toString();
    }

    /** The collection a command works on: its own value ({"find": "books"}), or a getMore's collection. */
    private static String collection(String name, BsonDocument command) {
        BsonValue value = command.get(name);
        if (value != null && value.isString()) return value.asString().getValue();
        BsonValue more = command.get("collection");
        return more != null && more.isString() ? more.asString().getValue() : null;
    }

    /** How many documents a find, an aggregate or a getMore returned. */
    private static Integer rows(BsonDocument reply) {
        BsonValue cursor = reply == null ? null : reply.get("cursor");
        if (cursor == null || !cursor.isDocument()) return null;
        BsonDocument found = cursor.asDocument();
        BsonValue batch = found.containsKey("firstBatch") ? found.get("firstBatch") : found.get("nextBatch");
        return batch != null && batch.isArray() ? batch.asArray().size() : null;
    }

    /** The settings Spring Boot builds its MongoClient from, with this listener: each client the app gets. */
    static final class Settings implements BeanPostProcessor {
        private final MongoCommands listener = new MongoCommands();

        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            if (!(bean instanceof MongoClientSettings settings)) return bean;
            if (settings.getCommandListeners().stream().anyMatch(MongoCommands.class::isInstance)) return bean;
            return MongoClientSettings.builder(settings).addCommandListener(listener).build();
        }
    }
}
