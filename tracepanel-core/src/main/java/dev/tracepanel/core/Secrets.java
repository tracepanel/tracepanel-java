package dev.tracepanel.core;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

    // A name read word by word, as the Python SDK reads it (tracepanel.core.masking.sensitive): split at _ - . [ ] and
    // where camelCase turns, so `author` isn't `auth`, `tokenizer` isn't `token`, and x-api-key is (x, api, key).
    private static final Pattern WORD_BREAKS =
            Pattern.compile("[^a-zA-Z0-9]+|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");
    private static final Set<String> CARD_WORDS = Set.of("pan", "cvc", "cvv", "cvn", "cvc2", "cvv2", "cardnumber", "cardnum", "cardno");
    private static final Set<String> WORDS = union(CARD_WORDS, Set.of(
            "pass", "passwd", "password", "passwords", "passphrase", "passcode", "passkey", "secret", "secrets",
            "secretkey", "token", "apikey", "apikeys", "auth", "oauth", "oauth2", "authorization", "authentication",
            "cookie", "cookies", "session", "sessions", "csrf", "xsrf", "signature", "signatures", "private"));
    /** Two words that make a secret together: card number, api key, refresh tokens... */
    private static final Set<String> PAIRS = pairs();
    /** The last word of a name about a secret, not the secret: password_length, token_type, cookie_name. */
    private static final Set<String> ABOUT = Set.of(
            "count", "total", "length", "len", "min", "max", "size", "age", "timeout", "ttl", "expiry", "expires",
            "expiration", "at", "type", "name", "field", "label", "required", "enabled");
    /** The first word of a yes or no: isPrivate, hasPassword. */
    private static final Set<String> YES_OR_NO = Set.of("is", "has");
    /** Words written together: accesstoken, csrftoken, clientsecret, newpassword, jsessionid. */
    private static final List<String> ENDS = List.of("token", "secret", "password", "passwd", "apikey", "sessionid", "sessid");
    /** ...and csrfmiddlewaretoken, sessionkey, cookiejar. */
    private static final List<String> STARTS = List.of("password", "apikey", "csrf", "xsrf", "session", "cookie");

    private Secrets() {}

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new HashSet<>(a);
        all.addAll(b);
        return Set.copyOf(all);
    }

    private static Set<String> pairs() {
        Set<String> pairs = new HashSet<>(Set.of("card number", "card num", "card no", "api key", "api keys"));
        for (String word : List.of("refresh", "access", "auth", "api", "bearer", "id", "session", "oauth", "csrf", "xsrf",
                "jwt", "client")) {
            pairs.add(word + " tokens");
        }
        return Set.copyOf(pairs);
    }

    /** A name's words, lowercase: X-CSRFToken is (x, csrf, token), cardNumber (card, number). */
    static List<String> words(String name) {
        List<String> words = new ArrayList<>();
        for (String word : WORD_BREAKS.split(name)) if (!word.isEmpty()) words.add(word.toLowerCase(Locale.ROOT));
        return words;
    }

    /**
     * Whether a secret goes under the name, read word by word as the Python SDK reads it: password, apiKey,
     * Authorization, csrftoken, sessionId, card[cvc]; not author, tokenizer, passwordLength nor hasPassword. A dump's
     * expressions, fields and keys are named so.
     */
    public static boolean sensitive(String name) {
        List<String> found = words(name);
        if (found.equals(List.of("tokens"))) return true;
        if (found.size() > 1 && (YES_OR_NO.contains(found.get(0)) || ABOUT.contains(found.get(found.size() - 1)))) {
            return false;
        }
        for (int i = 0; i < found.size(); i++) {
            String word = found.get(i);
            if (WORDS.contains(word)) return true;
            for (String end : ENDS) if (word.endsWith(end)) return true;
            for (String start : STARTS) if (word.startsWith(start)) return true;
            if (i > 0 && PAIRS.contains(found.get(i - 1) + " " + word)) return true;
        }
        return false;
    }

    /**
     * Whether `tokens` holds a model's counts, not credentials: a number, or numbers only (a collection, a map's
     * values, an array).
     */
    public static boolean counted(String name, Object value) {
        if (!words(name).equals(List.of("tokens"))) return false;
        Iterable<?> items;
        if (value instanceof Map<?, ?> map) items = map.values();
        else if (value instanceof Iterable<?> iterable) items = iterable;
        else if (value instanceof int[] || value instanceof long[] || value instanceof double[] || value instanceof float[]
                || value instanceof short[]) return true;
        else items = List.of(value == null ? "" : value);
        for (Object item : items) if (!(item instanceof Number)) return false;
        return true;
    }

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
