package dev.tracepanel.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Values under a secret's name, kept out of the traces as the Python SDK keeps them out: a password, a token, a key,
 * a session. Names are what tell: a URL's query parameters, and the columns a SQL statement compares or inserts.
 */
public final class Secrets {
    /** The Python SDK's names (tracepanel.core.frames.SENSITIVE). */
    static final Pattern NAME = Pattern.compile(
            "pass(word|wd)?|secret|token|api_?key|auth|cookie|session|csrf|signature|private", Pattern.CASE_INSENSITIVE);
    public static final String MASK = "********";

    // A placeholder compared with a column: password = ?, "api_key" <> ?, u.token IN (?.
    private static final Pattern COMPARED =
            Pattern.compile("[`\"\\[]?(\\w+)[`\"\\]]?\\s*(?:=|<>|!=|LIKE|IN\\s*\\()\\s*$", Pattern.CASE_INSENSITIVE);
    // INSERT INTO t (a, b) VALUES (?, ?): the columns, in the order of the values.
    private static final Pattern INSERT = Pattern.compile(
            "^\\s*INSERT\\s+INTO\\s+\\S+\\s*\\(([^)]*)\\)\\s*VALUES\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);

    private Secrets() {}

    /** Whether a value under this name (a log's field, a query parameter) is a secret. */
    public static boolean secret(String name) {
        return NAME.matcher(name).find();
    }

    /** A URL without its credentials (user:password@), and with the values of secret query parameters masked. */
    public static String url(URI url) {
        String query = url.getRawQuery();
        StringBuilder out = new StringBuilder();
        if (url.getScheme() != null) out.append(url.getScheme()).append("://");
        if (url.getRawAuthority() != null) out.append(url.getRawAuthority().replaceFirst("^.*@", ""));
        if (url.getRawPath() != null) out.append(url.getRawPath());
        if (query != null) {
            List<String> pairs = new ArrayList<>();
            for (String pair : query.split("&", -1)) {
                int equals = pair.indexOf('=');
                String name = equals < 0 ? pair : pair.substring(0, equals);
                pairs.add(equals >= 0 && NAME.matcher(name).find() ? name + "=" + MASK : pair);
            }
            out.append('?').append(String.join("&", pairs));
        }
        return out.toString();
    }

    /** The statement's parameters, with those its SQL binds to a secret's column masked. */
    public static List<Object> params(String sql, List<Object> values) {
        boolean[] secret = secretPlaceholders(sql, values.size());
        List<Object> masked = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) masked.add(secret[i] && values.get(i) != null ? MASK : values.get(i));
        return masked;
    }

    /** Which of the statement's ? placeholders (in order, outside its quoted text) stand for a secret's column. */
    static boolean[] secretPlaceholders(String sql, int count) {
        boolean[] secret = new boolean[count];
        List<Integer> marks = placeholders(sql);
        for (int i = 0; i < marks.size() && i < count; i++) {
            Matcher column = COMPARED.matcher(sql.substring(0, marks.get(i)));
            secret[i] = column.find() && NAME.matcher(column.group(1)).find();
        }
        Matcher insert = INSERT.matcher(sql);
        if (insert.find()) {
            String[] columns = insert.group(1).split(",");
            String[] values = insert.group(2).split(",");
            int before = placeholders(sql.substring(0, insert.start(2))).size();
            int seen = 0;
            for (int i = 0; i < columns.length && i < values.length; i++) {
                if (!values[i].trim().equals("?")) continue;
                int index = before + seen++;
                if (index < count && NAME.matcher(columns[i]).find()) secret[index] = true;
            }
        }
        return secret;
    }

    /** Where the ? placeholders are, outside quoted strings and identifiers. */
    private static List<Integer> placeholders(String sql) {
        List<Integer> marks = new ArrayList<>();
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
            } else if (c == '?') {
                marks.add(i);
            }
        }
        return marks;
    }
}
