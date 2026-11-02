package dev.tracepanel.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which statements EXPLAIN may run, the rule the agent, its console and every SDK share: a SELECT, or a WITH whose
 * CTEs and final statement only read. Its words are read outside quoted strings and names and comments, so a keyword
 * in a string changes nothing.
 *
 * <ul>
 *   <li>It starts (after its comments) with SELECT, or with WITH and ends in a SELECT.
 *   <li>It is one statement: nothing follows a {@code ;} but comments.
 *   <li>No INSERT, UPDATE, DELETE or MERGE anywhere (a data-modifying CTE) and no INTO ({@code SELECT … INTO} makes
 *       a table).
 *   <li>ANALYZE runs it, so a SELECT that locks rows ({@code FOR UPDATE}, {@code FOR SHARE}, {@code LOCK IN SHARE
 *       MODE}) is explained but not analyzed. ANALYZE runs in a transaction that is rolled back (Explain).
 * </ul>
 */
final class Explainable {
    static final String NOT_SELECT = "Only SELECT statements can be explained.";
    private static final String ONE_STATEMENT = "Only one statement can be explained at a time.";
    private static final Set<String> CHANGING = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");
    /** What can follow a WITH's CTEs: the statement they are for. */
    private static final Set<String> MAIN = Set.of("SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "VALUES", "TABLE");

    private Explainable() {}

    /** A word (upper-cased) or any other token (a quoted string or name, a number, a sign), with its depth in parentheses. */
    private record Token(String text, boolean word, int depth) {}

    /** Why a statement isn't explained (or with {@code analyze}, analyzed), or null when it is. */
    static String refusal(String statement, boolean analyze) {
        List<Token> tokens = tokens(statement);
        List<String> words = tokens.stream().map(token -> token.word() ? token.text() : "").toList();
        boolean with;
        if (!words.isEmpty() && words.get(0).equals("SELECT")) with = false;
        else if (!words.isEmpty() && words.get(0).equals("WITH")) with = true;
        else return NOT_SELECT;
        for (int at = 0; at < tokens.size(); at++) {
            Token token = tokens.get(at);
            if (token.depth() == 0 && !token.word() && token.text().equals(";")) {
                if (at + 1 < tokens.size()) return ONE_STATEMENT;
                break;
            }
        }
        int main = with ? -1 : 0;
        for (int at = 0; with && at < tokens.size(); at++) {
            Token token = tokens.get(at);
            if (token.word() && token.depth() == 0 && MAIN.contains(token.text())) {
                main = at;
                break;
            }
        }
        for (int at = 0; at < words.size(); at++) {
            String word = words.get(at);
            if (CHANGING.contains(word) && !(word.equals("UPDATE") && lock(words, at))) {
                return changes(word + (with && (main < 0 || at < main) ? " inside WITH" : ""));
            }
        }
        if (words.contains("INTO")) return changes("SELECT INTO");
        if (main < 0 || !words.get(main).equals("SELECT")) return NOT_SELECT;
        if (!analyze) return null;
        for (int at = 0; at < words.size(); at++) {
            String word = words.get(at);
            String locks = null;
            if (word.equals("UPDATE") && lock(words, at)) locks = "FOR UPDATE";
            else if (word.equals("SHARE") && lock(words, at)) locks = "FOR SHARE";
            else if (word.equals("SHARE") && previous(words, at).equals("IN") && at >= 2 && words.get(at - 2).equals("LOCK")) {
                locks = "LOCK IN SHARE MODE";
            }
            if (locks != null) return "This query locks rows (" + locks + "): it can be explained, not analyzed.";
        }
        return null;
    }

    private static String previous(List<String> words, int at) {
        return at > 0 ? words.get(at - 1) : "";
    }

    private static boolean lock(List<String> words, int at) {
        String before = previous(words, at);
        return before.equals("FOR") || before.equals("KEY");
    }

    private static String changes(String what) {
        return "This query changes data (" + what + "): it isn't explained.";
    }

    /**
     * The statement's tokens, without its comments and spaces: a quoted string or name ({@code '…'} with {@code ''},
     * {@code E'…'} with backslashes too, {@code "…"}, {@code `…`}, {@code […]}, PostgreSQL's {@code $tag$…$tag$}) is
     * one token whose text is never read. The inside of MySQL's {@code /*! … *}{@code /}, which MySQL runs, is read as
     * the statement.
     */
    private static List<Token> tokens(String statement) {
        int[] chars = statement.codePoints().toArray();
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        int at = 0;
        while (at < chars.length) {
            int c = chars[at];
            int next = at + 1 < chars.length ? chars[at + 1] : 0;
            String text;
            boolean word = false;
            int[] tag;
            if (Character.isWhitespace(c)) {
                at++;
                continue;
            } else if (c == '-' && next == '-') {
                at = past(chars, at + 2, new int[] {'\n'});
                continue;
            } else if (c == '/' && next == '*') {
                at = at + 2 < chars.length && chars[at + 2] == '!' ? at + 3 : past(chars, at + 2, new int[] {'*', '/'});
                continue;
            } else if (c == '\'') {
                // E'…' (PostgreSQL's escape string): a backslash escapes the next character.
                boolean escapes = at > 0 && (chars[at - 1] == 'E' || chars[at - 1] == 'e') && !tokens.isEmpty()
                        && tokens.get(tokens.size() - 1).word() && tokens.get(tokens.size() - 1).text().equals("E");
                at = quoted(chars, at, '\'', escapes);
                text = "'";
            } else if (c == '"' || c == '`') {
                at = quoted(chars, at, c, false);
                text = Character.toString(c);
            } else if (c == '[' && (Character.isLetter(next) || next == '_')) {
                at = past(chars, at + 1, new int[] {']'});
                text = "[";
            } else if (c == '$' && (tag = dollarTag(chars, at)) != null) {
                at = past(chars, at + tag.length, tag);
                text = "$";
            } else if (Character.isLetter(c) || c == '_') {
                int start = at;
                while (at < chars.length && (Character.isLetterOrDigit(chars[at]) || chars[at] == '_' || chars[at] == '$')) at++;
                text = new String(chars, start, at - start).toUpperCase(Locale.ROOT);
                word = true;
            } else if (c >= '0' && c <= '9') {
                while (at < chars.length && (Character.isLetterOrDigit(chars[at]) || chars[at] == '_' || chars[at] == '.')) at++;
                text = "0";
            } else {
                at++;
                text = Character.toString(c);
            }
            if (!word && text.equals(")")) depth = Math.max(0, depth - 1);
            tokens.add(new Token(text, word, depth));
            if (!word && text.equals("(")) depth++;
        }
        return tokens;
    }

    /** Where a quote opened at {@code at} ends: past its closing {@code quote} (doubled, it is one inside), or the end. */
    private static int quoted(int[] chars, int at, int quote, boolean escapes) {
        at++;
        while (at < chars.length) {
            int c = chars[at];
            if (escapes && c == '\\') at += 2;
            else if (c == quote && at + 1 < chars.length && chars[at + 1] == quote) at += 2;
            else if (c == quote) return at + 1;
            else at++;
        }
        return chars.length;
    }

    /** Past the first {@code end} from {@code from} on, or the statement's end. */
    private static int past(int[] chars, int from, int[] end) {
        for (int at = from; at + end.length <= chars.length; at++) {
            if (Arrays.equals(chars, at, at + end.length, end, 0, end.length)) return at + end.length;
        }
        return chars.length;
    }

    /** PostgreSQL's dollar quote opening at {@code at}: {@code $$} or {@code $tag$} (not a {@code $1} parameter). */
    private static int[] dollarTag(int[] chars, int at) {
        for (int end = at + 1; end < chars.length; end++) {
            int c = chars[end];
            if (c == '$') return Arrays.copyOfRange(chars, at, end + 1);
            boolean first = end == at + 1;
            if (!(Character.isLetter(c) || c == '_' || (!first && c >= '0' && c <= '9'))) return null;
        }
        return null;
    }
}
