package dev.tracepanel.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A statement's span name, its one-line form and its fingerprint, which the agent groups queries (and finds N+1
 * queries) by: the same rules in every Tracepanel SDK.
 */
public final class Sql {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    // A comment ("-- a note" to the line's end, sqlcommenter's block of tags), or a string or quoted name, whose "--"
    // and "/*" are its text: matched so a comment is told from a string that looks like one.
    private static final Pattern COMMENT =
            Pattern.compile("(?s)(--[^\\n]*)|(/\\*.*?\\*/)|'[^']*(?:''[^']*)*'|\"[^\"]*(?:\"\"[^\"]*)*\"|`[^`]*`");
    private static final Pattern IN_LIST =
            Pattern.compile("\\bIN \\((?:%s|\\?|\\$\\d+)(?:, ?(?:%s|\\?|\\$\\d+))*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE =
            Pattern.compile("\\b(?:FROM|INTO|UPDATE|JOIN)\\s+[`\"\\[]?([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT = Pattern.compile("^\\s*SELECT\\s+COUNT\\(", Pattern.CASE_INSENSITIVE);

    private Sql() {}

    /** The statement on one line, a line comment ("-- a note") closed so the line it ended still reads as SQL. */
    public static String statement(String sql) {
        String closed = sql.contains("--")
                ? COMMENT.matcher(sql).replaceAll(found -> Matcher.quoteReplacement(found.group(1) == null
                        ? found.group()
                        : "/* " + found.group(1).substring(2).trim().replace("*/", "* /") + " */"))
                : sql;
        return WHITESPACE.matcher(closed).replaceAll(" ").trim();
    }

    /** The one-line statement without its comments (an ORM's or sqlcommenter's tags, a note): what names and groups it. */
    static String uncommented(String sql) {
        if (!sql.contains("--") && !sql.contains("/*")) {
            return statement(sql);
        }
        return statement(COMMENT.matcher(sql).replaceAll(found -> found.group(1) == null && found.group(2) == null
                ? Matcher.quoteReplacement(found.group())
                : " "));
    }

    public static String fingerprint(String sql) {
        return IN_LIST.matcher(uncommented(sql)).replaceAll("IN (...)");
    }

    /** The verb and the table, the statement's comments left out. */
    public static String spanName(String commented) {
        String sql = uncommented(commented);
        String trimmed = sql.trim();
        String verb = COUNT.matcher(sql).find()
                ? "SELECT COUNT"
                : (trimmed.isEmpty() ? "SQL" : WHITESPACE.split(trimmed, 2)[0].toUpperCase(Locale.ROOT));
        Matcher table = TABLE.matcher(sql);
        return table.find() ? verb + " " + table.group(1) : verb;
    }

    /** A short hash of a statement's values: what tells duplicates (the same values) from repeats. */
    public static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
