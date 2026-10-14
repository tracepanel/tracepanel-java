package dev.tracepanel.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A statement's span name, its one-line form and its fingerprint, which the agent groups queries (and finds N+1
 * queries) by: the same rules in every Tracepanel SDK.
 */
public final class Sql {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern IN_LIST =
            Pattern.compile("\\bIN \\((?:%s|\\?|\\$\\d+)(?:, ?(?:%s|\\?|\\$\\d+))*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE =
            Pattern.compile("\\b(?:FROM|INTO|UPDATE|JOIN)\\s+[`\"\\[]?([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT = Pattern.compile("^\\s*SELECT\\s+COUNT\\(", Pattern.CASE_INSENSITIVE);

    private Sql() {}

    public static String statement(String sql) {
        return WHITESPACE.matcher(sql).replaceAll(" ").trim();
    }

    public static String fingerprint(String sql) {
        return IN_LIST.matcher(statement(sql)).replaceAll("IN (...)");
    }

    public static String spanName(String sql) {
        String trimmed = sql.trim();
        String verb = COUNT.matcher(sql).find()
                ? "SELECT COUNT"
                : (trimmed.isEmpty() ? "SQL" : WHITESPACE.split(trimmed, 2)[0].toUpperCase(Locale.ROOT));
        Matcher table = TABLE.matcher(sql);
        return table.find() ? verb + " " + table.group(1) : verb;
    }
}
