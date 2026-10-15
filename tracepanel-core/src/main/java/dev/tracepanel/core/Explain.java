package dev.tracepanel.core;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * The console's `api/explain?trace=&span=&analyze=`: EXPLAIN of a recorded query, run by the app on the database it
 * came from (the agent has no way to it). The statement and its parameters come from the stored trace; the plan goes
 * back as lines with their depth and what to look at (a full scan).
 */
final class Explain {
    private static final Pattern SELECT = Pattern.compile("^\\s*(SELECT|WITH)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEQ_SCAN = Pattern.compile("Seq Scan on (\\w+)");
    private static final Pattern SQLITE_SCAN = Pattern.compile("^SCAN (?:TABLE )?(\\w+)(?!.*\\bUSING\\b)");

    private Explain() {}

    /** A plan's line: how deep in the plan, its text, and a warning when it reads a whole table. */
    record Line(int depth, String text, String warning) {}

    /** What the app adds to its agent's console: EXPLAIN once it has a database; no switching users (Django's). */
    static Console.Answer capabilities() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("explain", Jdbc.hasDatabases());
        capabilities.put("users", false);
        return json(200, capabilities);
    }

    static Console.Answer answer(Tracepanel tracepanel, String query) {
        Map<String, String> params = query(query);
        Map<String, Object> span = span(tracepanel, params.getOrDefault("trace", ""), params.getOrDefault("span", ""));
        if (span == null || !"sql".equals(span.get("kind")) || !(span.get("attrs") instanceof Map<?, ?> attrs)) {
            return json(404, Map.of("error", "not found"));
        }
        String alias = attrs.get("db.alias") instanceof String named ? named : "default";
        DataSource database = Jdbc.database(alias);
        if (database == null) return json(422, Map.of("error", "The app has no database " + alias + " to run EXPLAIN on."));
        String statement = String.valueOf(attrs.get("db.statement"));
        if (!SELECT.matcher(statement).find()) return json(422, Map.of("error", "Only SELECT statements can be explained."));
        if (!(attrs.get("db.params") instanceof List<?> values)) {
            return json(422, Map.of("error", "Parameters of this query were not recorded."));
        }
        try {
            return json(200, run(database, alias, statement, values, "1".equals(params.get("analyze"))));
        } catch (SQLException e) {
            return json(422, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private static Map<String, Object> run(DataSource database, String alias, String statement, List<?> values, boolean analyze)
            throws SQLException {
        try (Connection connection = database.getConnection()) {
            String vendor = connection.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).replace(" ", "");
            boolean analyzed = analyze && (vendor.equals("postgresql") || vendor.equals("mysql") || vendor.equals("h2"));
            String prefix = switch (vendor) {
                case "sqlite" -> "EXPLAIN QUERY PLAN ";
                case "postgresql" -> analyzed ? "EXPLAIN (ANALYZE, BUFFERS) " : "EXPLAIN ";
                default -> analyzed ? "EXPLAIN ANALYZE " : "EXPLAIN ";
            };
            // EXPLAIN ANALYZE runs the query: whatever it does is rolled back.
            boolean auto = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement explain = connection.prepareStatement(prefix + statement)) {
                for (int i = 0; i < values.size(); i++) bind(explain, i + 1, values.get(i), vendor);
                try (ResultSet rows = explain.executeQuery()) {
                    Map<String, Object> plan = new LinkedHashMap<>();
                    plan.put("vendor", vendor);
                    plan.put("alias", alias);
                    plan.put("analyzed", analyzed);
                    plan.put("lines", lines(rows, vendor).stream().map(Explain::line).toList());
                    return plan;
                }
            } finally {
                connection.rollback();
                connection.setAutoCommit(auto);
            }
        }
    }

    /** A recorded value back as the query had it; text as untyped in PostgreSQL, which then reads it as its column's. */
    private static void bind(PreparedStatement statement, int index, Object value, String vendor) throws SQLException {
        if (value instanceof String text && vendor.equals("postgresql")) statement.setObject(index, text, Types.OTHER);
        else statement.setObject(index, value);
    }

    private static List<Line> lines(ResultSet rows, String vendor) throws SQLException {
        ResultSetMetaData columns = rows.getMetaData();
        List<Line> lines = new ArrayList<>();
        if (vendor.equals("sqlite")) {
            // (id, parent, notused, detail): depth follows the parent chain.
            Map<Integer, Integer> depth = new LinkedHashMap<>(Map.of(0, -1));
            while (rows.next()) {
                int level = depth.getOrDefault(rows.getInt(2), -1) + 1;
                depth.put(rows.getInt(1), level);
                String detail = rows.getString(4);
                Matcher scan = SQLITE_SCAN.matcher(detail);
                lines.add(new Line(level, detail, scan.find() ? "Full scan of " + scan.group(1) + ": no index is used" : null));
            }
        } else if (columns.getColumnCount() == 1) {
            // A plan as text (PostgreSQL, H2, MySQL's ANALYZE): a row per line, or one row of many lines.
            while (rows.next()) for (String text : String.valueOf(rows.getString(1)).split("\n")) lines.add(textLine(text));
        } else {
            while (rows.next()) lines.add(new Line(0, row(rows, columns), null));
        }
        return lines;
    }

    /** "->" nodes indented 6 a level, their details (Filter:, Buffers:) 2 deeper, as PostgreSQL writes a plan. */
    private static Line textLine(String text) {
        String trimmed = text.strip();
        int indent = text.length() - text.stripLeading().length();
        boolean node = trimmed.startsWith("->");
        String body = node ? trimmed.substring(2).strip() : trimmed;
        int depth = node ? (indent + 2) / 6 : (indent + 4) / 6;
        Matcher scan = SEQ_SCAN.matcher(body);
        return new Line(depth, body, scan.find() ? "Sequential scan of " + scan.group(1) + ": no index is used" : null);
    }

    /** A tabular plan's row (MySQL's EXPLAIN) as `column=value` pairs. */
    private static String row(ResultSet rows, ResultSetMetaData columns) throws SQLException {
        List<String> pairs = new ArrayList<>();
        for (int i = 1; i <= columns.getColumnCount(); i++) {
            Object value = rows.getObject(i);
            if (value != null) pairs.add(columns.getColumnLabel(i) + "=" + value);
        }
        return String.join("  ", pairs);
    }

    private static Map<String, Object> line(Line line) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("depth", line.depth());
        out.put("text", line.text());
        out.put("warning", line.warning());
        return out;
    }

    /** The span of a stored trace, as the agent gives it; null when there is no such trace or span. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> span(Tracepanel tracepanel, String traceId, String spanId) {
        if (traceId.isEmpty() || !traceId.matches("[0-9A-Za-z]+")) return null;
        Console.Answer stored = Console.forward(tracepanel, "GET", "api/traces/" + traceId, null, null, "127.0.0.1");
        if (stored.status() != 200) return null;
        try {
            if (!(Json.read(new String(stored.body(), StandardCharsets.UTF_8)) instanceof Map<?, ?> trace)) return null;
            if (!(trace.get("spans") instanceof List<?> spans)) return null;
            for (Object span : spans) {
                if (span instanceof Map<?, ?> found && spanId.equals(found.get("id"))) return (Map<String, Object>) found;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return null;
    }

    private static Map<String, String> query(String query) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                params.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return params;
    }

    private static Console.Answer json(int status, Map<String, ?> body) {
        return new Console.Answer(status, "application/json", Map.of(), Json.write(body).getBytes(StandardCharsets.UTF_8));
    }
}
