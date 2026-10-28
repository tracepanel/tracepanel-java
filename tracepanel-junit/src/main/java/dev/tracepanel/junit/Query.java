package dev.tracepanel.junit;

import java.util.List;
import java.util.Map;

/**
 * A query a test made, or a request it made did.
 *
 * @param statement its SQL, on one line, cut after 300 characters
 * @param origin the line of the project's code that ran it ("src/main/java/shop/Books.java:21 in list"), or null
 */
public record Query(String statement, String origin) {
    // A failure lists this many queries, and says how many more there were.
    static final int MAX_LISTED = 20;
    // A statement longer than this is cut in a failure's message.
    static final int MAX_STATEMENT = 300;

    /** A span's statement as a failure shows it: its whitespace one space, cut when long. */
    static String statement(Map<String, Object> span) {
        Object attrs = span.get("attrs");
        Object text = attrs instanceof Map<?, ?> map ? map.get("db.statement") : null;
        if (text == null || String.valueOf(text).isEmpty()) text = span.get("name");
        String line = String.join(" ", String.valueOf(text).trim().split("\\s+"));
        return line.length() <= MAX_STATEMENT ? line : line.substring(0, MAX_STATEMENT) + "…";
    }

    static Query of(Map<String, Object> span) {
        Object attrs = span.get("attrs");
        Object origin = attrs instanceof Map<?, ?> map ? map.get("code.origin") : null;
        return new Query(statement(span), origin == null ? null : String.valueOf(origin));
    }

    /** Queries as a failure lists them: the statement, and where the project's code made it. */
    static String listed(List<Query> queries) {
        StringBuilder lines = new StringBuilder();
        for (Query query : queries.subList(0, Math.min(queries.size(), MAX_LISTED))) {
            if (lines.length() > 0) lines.append('\n');
            lines.append("  ").append(query.statement());
            if (query.origin() != null && !query.origin().isEmpty()) lines.append("\n    at ").append(query.origin());
        }
        if (queries.size() > MAX_LISTED) lines.append("\n  … and ").append(queries.size() - MAX_LISTED).append(" more");
        return lines.toString();
    }

    static String tooMany(List<Query> made, int limit) {
        return made.size() + " queries, at most " + limit + " expected:\n" + listed(made);
    }
}
