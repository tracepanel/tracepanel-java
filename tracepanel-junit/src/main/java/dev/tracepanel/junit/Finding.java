package dev.tracepanel.junit;

import java.util.ArrayList;
import java.util.List;

/**
 * An issue the agent found in a test's trace, or in the trace of a request the test made.
 *
 * @param kind n+1, duplicates, slow-sql, ...
 * @param title what the console calls it: "N+1: 5 similar queries · SELECT ... FROM book WHERE author_id = ?"
 * @param culprit where in the project's code: "src/main/java/shop/Books.java:21 in list", or null
 * @param hint how to fix it, or null
 * @param count how many times it happened
 * @param query the statement of its first query, for an issue about SQL; else null
 * @param traceId the trace it is in
 * @param traceName that trace's name: the test's, or a request's ("GET /books")
 * @param url the trace in the agent's console
 */
public record Finding(String kind, String title, String culprit, String hint, int count, String query, String traceId,
        String traceName, String url) {

    /** The finding as a failure lists it; the trace it is in unless it is the test's own (`test`, its id). */
    public String describe(String test) {
        List<String> lines = new ArrayList<>();
        lines.add(title);
        if (query != null && !query.isEmpty()) lines.add("  " + query);
        if (culprit != null && !culprit.isEmpty()) lines.add("  at " + culprit);
        if (!traceId.equals(test)) lines.add("  in " + traceName + " → " + url);
        if (hint != null && !hint.isEmpty()) lines.add("  " + hint);
        return String.join("\n", lines);
    }
}
