package dev.tracepanel.core;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tracepanel.dump: what a developer prints to look at, as python-devtools' debug() prints it. The call's file, line
 * and method, then each value with the expression it was written as, its pretty text, its type and its length, on
 * stderr; in a trace also a "dump" log record on the current span, which the console always shows.
 */
final class Dumps {
    /** How much of one value's text is kept, in characters, as the Python SDK keeps; a longer one is cut and marked. */
    static final int MAX_REPR = 16 * 1024;
    static final String CUT = "… (truncated)";
    /** How deep into a value's fields, elements and entries its text goes. */
    static final int MAX_DEPTH = 6;
    /** The elements of one collection, array or map shown; the rest are counted. */
    static final int MAX_ITEMS = 1000;
    /** A value's text fits on one line up to this width, else it goes over lines. */
    private static final int WIDTH = 80;
    private static final String INDENT = "    ";
    /** The JDK's classes, whose fields are never opened: their toString says what they hold. */
    private static final List<String> JDK = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");
    private static final Pattern CALL = Pattern.compile("(?<![\\w$])dump\\s*\\(");
    private static final Pattern NUMBER = Pattern.compile(
            "[-+]?(0[xX][0-9a-fA-F_]+|0[bB][01_]+|(\\d[\\d_]*)?\\.?\\d[\\d_]*([eE][-+]?\\d+)?)[lLfFdDuU]*");
    /** The lines of the call's source looked at past the line the call is on, for its arguments. */
    private static final int MAX_CALL_LINES = 40;

    private static final Map<String, Optional<Path>> SOURCES = new ConcurrentHashMap<>();
    private static final Map<Path, Source> LINES = new ConcurrentHashMap<>();

    private Dumps() {}

    private record Source(FileTime modified, List<String> lines) {}

    /** What one dump call printed and records: its text and its record's attrs. */
    record Dump(String message, Map<String, Object> attrs) {}

    static void dump(Object[] values, PrintStream out) {
        Dump dump;
        try {
            dump = build(values == null ? new Object[] {null} : values, caller());
        } catch (RuntimeException | StackOverflowError e) {
            // A dump never breaks the code that asked for it.
            out.println("tracepanel: could not dump: " + e);
            return;
        }
        out.println(dump.message());
        Trace trace = Tracepanel.current();
        if (trace != null) trace.log("debug", "tracepanel", dump.message(), null, dump.attrs());
    }

    /** The frame that called Tracepanel.dump. */
    private static StackWalker.StackFrame caller() {
        return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
                .walk(frames -> frames.filter(frame -> !frame.getClassName().equals(Dumps.class.getName())
                                && !frame.getClassName().equals(Tracepanel.class.getName()))
                        .findFirst())
                .orElse(null);
    }

    static Dump build(Object[] values, StackWalker.StackFrame frame) {
        Path root = root();
        String className = frame == null ? "?" : frame.getClassName();
        String fileName = frame == null ? null : frame.getFileName();
        int line = frame == null ? 0 : frame.getLineNumber();
        String method = frame == null ? "?" : frame.getMethodName();
        String packagePath = packagePath(className, fileName);
        Optional<Path> file = source(root, className, fileName, frame == null ? null : declaringClass(frame));
        String path = file.map(found -> shown(root, found)).orElse(packagePath);
        List<String> exprs = file.map(found -> exprs(lines(found), line, values.length)).orElse(null);

        List<Map<String, Object>> args = new ArrayList<>();
        StringBuilder message = new StringBuilder(path).append(':').append(line).append(' ').append(method);
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            String expr = exprs == null ? null : exprs.get(i);
            String type = value == null ? "null" : typeName(value.getClass());
            boolean secret = expr != null && secretExpr(expr, value);
            String repr = secret ? Secrets.MASK : capped(repr(value));
            Integer len = secret ? null : length(value);
            Map<String, Object> arg = new LinkedHashMap<>();
            arg.put("expr", expr);
            arg.put("repr", repr);
            arg.put("type", type);
            arg.put("len", len);
            args.add(arg);
            message.append('\n').append(INDENT);
            if (expr != null) message.append(expr).append(": ");
            message.append(repr.replace("\n", "\n" + INDENT)).append(" (").append(type).append(')');
            if (len != null) message.append(" len=").append(len);
        }
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("log.kind", "dump");
        attrs.put("dump.lang", fileName != null && (fileName.endsWith(".kt") || fileName.endsWith(".kts")) ? "kotlin" : "java");
        attrs.put("dump.args", args);
        attrs.put("code.origin", path + ":" + line + " in " + method);
        return new Dump(message.toString(), attrs);
    }

    private static Path root() {
        Tracepanel tracepanel = Tracepanel.get();
        if (tracepanel != null) return tracepanel.config().root();
        return Path.of(System.getProperty("user.dir")).toAbsolutePath();
    }

    static String capped(String text) {
        return text.length() <= MAX_REPR ? text : text.substring(0, MAX_REPR) + CUT;
    }

    /**
     * A value's type as the developer knows it: the class's simple name, but for a JDK class that isn't public
     * (List.of's ListN, Collections' wrappers, Arrays.asList's), a Kotlin library's internal one (emptyList's
     * EmptyList), an anonymous class or a lambda, the public type it is: List, Set, Map, Map.Entry or Collection,
     * else its public superclass or interface, else "lambda".
     */
    static String typeName(Class<?> type) {
        if (type.isArray()) return typeName(type.getComponentType()) + "[]";
        boolean lambda = type.isHidden() || type.isSynthetic() || type.getName().contains("$$Lambda");
        boolean hidden = lambda || type.isAnonymousClass() || jdk(type) && !visible(type) || kotlinInternal(type);
        if (!hidden) return simpleName(type);
        for (Class<?> known : KNOWN) if (known.isAssignableFrom(type)) return known == Map.Entry.class ? "Map.Entry" : known.getSimpleName();
        for (Class<?> at = type.getSuperclass(); at != null && at != Object.class; at = at.getSuperclass()) {
            if (visible(at) && !kotlinInternal(at) && !at.getName().startsWith("kotlin.jvm.internal.")) return simpleName(at);
        }
        for (Class<?> face : type.getInterfaces()) if (visible(face)) return simpleName(face);
        return lambda ? "lambda" : simpleName(type);
    }

    /** The public types a hidden class is named by, the most specific first. */
    private static final List<Class<?>> KNOWN = List.of(List.class, Set.class, Map.class, Map.Entry.class, Collection.class);

    private static String simpleName(Class<?> type) {
        String simple = type.getSimpleName();
        if (!simple.isEmpty()) return simple;
        String name = type.getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** Whether the class, and each class it is declared in, is public. */
    private static boolean visible(Class<?> type) {
        for (Class<?> at = type; at != null; at = at.getDeclaringClass()) {
            if (!Modifier.isPublic(at.getModifiers())) return false;
        }
        return !type.isAnonymousClass() && !type.isHidden();
    }

    /** Kotlin's collections' own classes, internal to its library but public in its bytecode: EmptyList, ListBuilder. */
    private static boolean kotlinInternal(Class<?> type) {
        String name = type.getName();
        return name.startsWith("kotlin.collections.") && !name.equals("kotlin.collections.ArrayDeque");
    }

    /** The length of a value that has one: a collection, a map, an array, a string. */
    static Integer length(Object value) {
        if (value instanceof Collection<?> collection) return collection.size();
        if (value instanceof Map<?, ?> map) return map.size();
        if (value instanceof CharSequence text) return text.length();
        if (value != null && value.getClass().isArray()) return Array.getLength(value);
        return null;
    }

    // The source: the caller's file in any module of the build the project is in (DumpSources).

    private static String packagePath(String className, String fileName) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot).replace('.', '/') + "/";
        String file = fileName != null ? fileName : className.substring(dot + 1).replaceAll("\\$.*", "") + ".java";
        return pkg + file;
    }

    /** Found sources are kept; a miss is asked again, as the file may come (DumpSources rebuilds its index then). */
    private static Optional<Path> source(Path root, String className, String fileName, Class<?> type) {
        String key = root + "|" + className + "|" + fileName;
        Optional<Path> found = SOURCES.get(key);
        if (found != null && Files.isRegularFile(found.get())) return found;
        found = DumpSources.find(root, className, fileName, type);
        found.ifPresent(path -> SOURCES.put(key, Optional.of(path)));
        return found;
    }

    /** The class of the frame, when the walker kept it. */
    private static Class<?> declaringClass(StackWalker.StackFrame frame) {
        try {
            return frame.getDeclaringClass();
        } catch (UnsupportedOperationException e) {
            return null;
        }
    }

    /** A source's path as the console shows it: from the project's root, else from its build's root. */
    private static String shown(Path root, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path project = root.toAbsolutePath().normalize();
        Path base = absolute.startsWith(project) ? project : DumpSources.buildRoot(project);
        if (!absolute.startsWith(base)) base = project;
        return base.relativize(absolute).toString().replace('\\', '/');
    }

    private static List<String> lines(Path file) {
        try {
            FileTime modified = Files.getLastModifiedTime(file);
            Source cached = LINES.get(file);
            if (cached != null && cached.modified().equals(modified)) return cached.lines();
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            LINES.put(file, new Source(modified, lines));
            return lines;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    // The arguments as written: the dump( call on the caller's line (or just above it, for a call over lines).

    /** Each argument's expression, null for a literal; null for all when the call can't be read. */
    static List<String> exprs(List<String> lines, int line, int count) {
        return exprs(lines, line, 0, count);
    }

    /**
     * As {@link #exprs(List, int, int)}, for the call at a 1-based column of the line when the frame knows it (0 when
     * not, as a JVM frame never does): of several calls on the line, the one whose text covers it, else the first.
     */
    static List<String> exprs(List<String> lines, int line, int column, int count) {
        if (line <= 0 || line > lines.size()) return null;
        for (int at = line; at >= Math.max(1, line - 5); at--) {
            Matcher call = CALL.matcher(lines.get(at - 1));
            if (!call.find()) continue;
            StringBuilder text = new StringBuilder();
            for (int i = at - 1; i < Math.min(lines.size(), at - 1 + MAX_CALL_LINES); i++) text.append(lines.get(i)).append('\n');
            int[] end = new int[1];
            List<String> args = arguments(text.toString(), call.end(), end);
            if (at == line && column > 0 && args != null && !covers(lines.get(at - 1), call.start(), end[0], column)) {
                // The text starts with the call's line: a later call on it, from past the first one's ).
                String lineText = lines.get(at - 1);
                Matcher other = CALL.matcher(lineText);
                int from = end[0];
                while (from < lineText.length() && other.find(from)) {
                    List<String> found = arguments(text.toString(), other.end(), end);
                    if (found == null) break;
                    if (covers(lineText, other.start(), end[0], column)) {
                        args = found;
                        break;
                    }
                    from = end[0];
                }
            }
            if (args == null) return null;
            if (args.size() == 1 && args.get(0).isBlank()) args = List.of();
            if (args.size() != count) return null;
            List<String> exprs = new ArrayList<>(count);
            for (String arg : args) {
                String expr = arg.strip().replaceAll("\\s*\\n\\s*", " ");
                exprs.add(literal(expr) ? null : expr);
            }
            return exprs;
        }
        return null;
    }

    /**
     * The arguments of a call whose ( ends just before `from`, split at its own commas, without their comments; null
     * when it doesn't close.
     */
    static List<String> arguments(String text, int from) {
        return arguments(text, from, new int[1]);
    }

    /** As {@link #arguments(String, int)}, with the index past the call's ) put in closed[0]. */
    private static List<String> arguments(String text, int from, int[] closed) {
        List<String> args = new ArrayList<>();
        StringBuilder arg = new StringBuilder();
        int depth = 0;
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<') {
                int close = typeArguments(text, i);
                if (close > 0) {
                    arg.append(text, i, close);
                    i = close;
                    continue;
                }
            }
            if (c == '"' || c == '\'') {
                int end = skipQuoted(text, i);
                if (end < 0) return null;
                arg.append(text, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/') {
                int end = text.indexOf('\n', i);
                if (end < 0) return null;
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int end = text.indexOf("*/", i + 2);
                if (end < 0) return null;
                arg.append(' ');
                i = end + 2;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    if (c != ')') return null;
                    args.add(arg.toString());
                    closed[0] = i + 1;
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(arg.toString());
                arg.setLength(0);
                i++;
                continue;
            }
            arg.append(c);
            i++;
        }
        return null;
    }

    /** Whether a call that starts at `start` (with its receiver, as `Tracepanel.`) and ends before `end` covers a 1-based column. */
    private static boolean covers(String line, int start, int end, int column) {
        while (start > 0 && (Character.isJavaIdentifierPart(line.charAt(start - 1)) || line.charAt(start - 1) == '.')) start--;
        return column - 1 >= start && column - 1 < end;
    }

    /**
     * Past the type arguments whose < is at `at`, as `mapOf<String, Int>` or `List.<String>of` have: -1 when the < is a
     * comparison. It is a type argument list when it follows a name or a dot, closes before anything a type can't
     * hold (an operator other than ->, a literal, a string), and its > is followed by what follows a type: a (, a dot,
     * a :: , a ), a comma; with a space between, not a name or a value, so `a < b, c > d` stays two comparisons.
     */
    static int typeArguments(String text, int at) {
        if (at == 0) return -1;
        char before = text.charAt(at - 1);
        boolean afterDot = before == '.';
        if (!afterDot) {
            int start = at;
            while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
            if (start == at || !Character.isJavaIdentifierStart(text.charAt(start))) return -1;
        }
        int depth = 0;
        int parens = 0;
        int i = at;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
                if (depth == 0) {
                    i++;
                    break;
                }
            } else if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '>') {
                i++;
            } else if (c == '(') {
                parens++;
            } else if (c == ')') {
                if (--parens < 0) return -1;
            } else if (Character.isJavaIdentifierStart(c)) {
                while (i + 1 < text.length() && Character.isJavaIdentifierPart(text.charAt(i + 1))) i++;
            } else if (!(Character.isWhitespace(c) || c == '.' || c == ',' || c == '?' || c == '*' || c == '['
                    || c == ']' || c == '@')) {
                // A digit that starts a value, an operator, a string, a ; : none is in a type.
                return -1;
            }
            i++;
        }
        if (depth != 0 || parens != 0) return -1;
        if (i >= text.length()) return i;
        char next = text.charAt(i);
        if (next == '(' || next == '.' || next == ':' || next == ')' || next == ',' || next == '[' || next == '?'
                || next == '{') {
            return i;
        }
        if (afterDot && Character.isJavaIdentifierStart(next)) return i;
        if (!Character.isWhitespace(next)) return -1;
        int after = i;
        while (after < text.length() && Character.isWhitespace(text.charAt(after))) after++;
        if (after >= text.length()) return i;
        char following = text.charAt(after);
        return following == ')' || following == ',' || following == '{' || following == '&' || following == '|'
                || following == '?' || following == ':' || following == '=' || following == ';' ? i : -1;
    }

    /**
     * Past a string or a char literal that starts at `at`: Java's and Kotlin's, text blocks and raw strings, a
     * Kotlin template's ${...} with its own strings inside. -1 when it doesn't end.
     */
    private static int skipQuoted(String text, int at) {
        char quote = text.charAt(at);
        if (quote == '"' && text.startsWith("\"\"\"", at)) {
            int i = at + 3;
            while (i < text.length()) {
                if (text.charAt(i) == '\\') {
                    i += 2;
                } else if (text.startsWith("${", i)) {
                    i = skipTemplate(text, i + 2);
                    if (i < 0) return -1;
                } else if (text.startsWith("\"\"\"", i)) {
                    i += 3;
                    // A raw string may end in more quotes than three: they are its own.
                    while (i < text.length() && text.charAt(i) == '"') i++;
                    return i;
                } else {
                    i++;
                }
            }
            return -1;
        }
        int i = at + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') return -1;
            if (c == '\\') {
                i += 2;
            } else if (quote == '"' && text.startsWith("${", i)) {
                i = skipTemplate(text, i + 2);
                if (i < 0) return -1;
            } else if (c == quote) {
                return i + 1;
            } else {
                i++;
            }
        }
        return -1;
    }

    /** Past a Kotlin template's ${...} whose code starts at `from`: -1 when it doesn't end. */
    private static int skipTemplate(String text, int from) {
        int depth = 0;
        int i = from;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(text, i);
                if (i < 0) return -1;
                continue;
            }
            if (c == '{') depth++;
            if (c == '}') {
                if (depth == 0) return i + 1;
                depth--;
            }
            i++;
        }
        return -1;
    }

    /** A value written as it is: a number, a string or char without a template, true, false, null. */
    static boolean literal(String expr) {
        if (expr.equals("true") || expr.equals("false") || expr.equals("null")) return true;
        if (NUMBER.matcher(expr).matches()) return true;
        if (expr.length() >= 2 && (expr.charAt(0) == '"' || expr.charAt(0) == '\'')
                && skipQuoted(expr, 0) == expr.length()) {
            return !expr.contains("$");
        }
        return false;
    }

    // The value's text: pretty, over lines when it doesn't fit on one.

    /** A value's pretty text, as long as it takes: the caller cuts it at MAX_REPR. */
    static String repr(Object value) {
        StringBuilder out = new StringBuilder();
        Node node = node(value, 0, new IdentityHashMap<>());
        write(node, 0, out);
        return out.toString();
    }

    /** A value's text as a tree: a leaf, or a group of items between an open and a close, each with a label. */
    private static final class Node {
        final String label;
        final String text;
        final String open;
        final List<Node> items;
        final String close;
        private int flat = -1;

        private Node(String label, String text, String open, List<Node> items, String close) {
            this.label = label;
            this.text = text;
            this.open = open;
            this.items = items;
            this.close = close;
        }

        static Node leaf(String text) {
            return new Node("", text, null, null, null);
        }

        static Node group(String open, List<Node> items, String close) {
            return new Node("", null, open, items, close);
        }

        Node labeled(String name) {
            return new Node(name + label, text, open, items, close);
        }

        /** Its length on one line, past the width: Integer.MAX_VALUE when it holds a line break. */
        int flat() {
            if (flat >= 0) return flat;
            long length;
            if (text != null) {
                length = text.indexOf('\n') >= 0 ? Integer.MAX_VALUE : label.length() + text.length();
            } else {
                length = label.length() + open.length() + close.length();
                for (Node item : items) {
                    length += item.flat() + 2L;
                    if (length > WIDTH * 4L) break;
                }
            }
            flat = (int) Math.min(length, Integer.MAX_VALUE);
            return flat;
        }
    }

    private static void write(Node node, int indent, StringBuilder out) {
        out.append(node.label);
        if (node.text != null) {
            out.append(node.text);
            return;
        }
        boolean oneLine = node.items.isEmpty() || indent * INDENT.length() + node.flat() <= WIDTH;
        out.append(node.open);
        for (int i = 0; i < node.items.size(); i++) {
            if (out.length() > MAX_REPR) return;
            if (oneLine) {
                if (i > 0) out.append(", ");
            } else {
                if (i > 0) out.append(',');
                out.append('\n').append(INDENT.repeat(indent + 1));
            }
            write(node.items.get(i), indent + 1, out);
        }
        if (!oneLine) out.append('\n').append(INDENT.repeat(indent));
        out.append(node.close);
    }

    private static Node node(Object value, int depth, IdentityHashMap<Object, Boolean> path) {
        if (value == null) return Node.leaf("null");
        if (value instanceof CharSequence text) return Node.leaf(quoted(text.toString(), '"'));
        if (value instanceof Character c) return Node.leaf(quoted(c.toString(), '\''));
        if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return Node.leaf(text(value));
        Class<?> type = value.getClass();
        boolean container = value instanceof Collection<?> || value instanceof Map<?, ?> || type.isArray()
                || value instanceof Optional<?> || type.isRecord() || !jdk(type) && !ownToString(type);
        if (!container) return Node.leaf(text(value));
        String name = typeName(type);
        if (path.containsKey(value)) return Node.leaf("<cycle: " + name + ">");
        if (depth >= MAX_DEPTH) return Node.leaf(value instanceof Map<?, ?> ? "{…}"
                : value instanceof Collection<?> || type.isArray() ? "[…]" : type.isRecord() ? name + "[…]" : name + "(…)");
        path.put(value, Boolean.TRUE);
        try {
            return container(value, type, name, depth + 1, path);
        } finally {
            path.remove(value);
        }
    }

    private static Node container(Object value, Class<?> type, String name, int depth, IdentityHashMap<Object, Boolean> path) {
        if (value instanceof Optional<?> optional) {
            return optional.isEmpty() ? Node.leaf("Optional.empty")
                    : Node.group("Optional[", List.of(node(optional.get(), depth, path)), "]");
        }
        if (value instanceof Collection<?> collection) {
            List<Node> items = new ArrayList<>();
            for (Object item : collection) {
                if (items.size() == MAX_ITEMS) break;
                items.add(node(item, depth, path));
            }
            more(items, collection.size());
            return Node.group("[", items, "]");
        }
        if (type.isArray()) {
            int length = Array.getLength(value);
            List<Node> items = new ArrayList<>();
            for (int i = 0; i < length && i < MAX_ITEMS; i++) items.add(node(Array.get(value, i), depth, path));
            more(items, length);
            return Node.group("[", items, "]");
        }
        if (value instanceof Map<?, ?> map) {
            List<Node> items = new ArrayList<>();
            for (Map.Entry<?, ?> entry : sorted(map)) {
                if (items.size() == MAX_ITEMS) break;
                StringBuilder key = new StringBuilder();
                write(node(entry.getKey(), depth, path), 0, key);
                Object rawKey = entry.getKey();
                String keyName = rawKey instanceof CharSequence || rawKey instanceof Enum<?> ? rawKey.toString() : null;
                items.add(named(entry.getValue(), keyName, depth, path).labeled(key.toString().replace("\n", " ") + "="));
            }
            more(items, map.size());
            return Node.group("{", items, "}");
        }
        if (type.isRecord()) {
            List<Node> items = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                Method accessor = component.getAccessor();
                Object field;
                try {
                    if (!accessor.canAccess(value) && !accessor.trySetAccessible()) return Node.leaf(text(value));
                    field = accessor.invoke(value);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return Node.leaf(text(value));
                }
                items.add(named(field, component.getName(), depth, path).labeled(component.getName() + "="));
            }
            return Node.group(name + "[", items, "]");
        }
        // A class of the app's or a library's without a toString of its own: its fields, its superclasses' first.
        List<Field> fields = new ArrayList<>();
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            if (jdk(at)) return Node.leaf(text(value));
            List<Field> own = new ArrayList<>();
            for (Field field : at.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) continue;
                if (!field.trySetAccessible()) return Node.leaf(text(value));
                own.add(field);
            }
            fields.addAll(0, own);
        }
        List<Node> items = new ArrayList<>();
        for (Field field : fields) {
            Object fieldValue;
            try {
                fieldValue = field.get(value);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return Node.leaf(text(value));
            }
            items.add(named(fieldValue, field.getName(), depth, path).labeled(field.getName() + "="));
        }
        return Node.group(name + "(", items, ")");
    }

    /** A field's, a component's or an entry's value: masked under a secret's name (a token, apiKey, password). */
    private static Node named(Object value, String name, int depth, IdentityHashMap<Object, Boolean> path) {
        if (name != null && secret(name, value)) return Node.leaf(Secrets.MASK);
        return node(value, depth, path);
    }

    /** Whether a value under this name is a secret's: not a model's counts under `tokens` (Secrets.counted). */
    static boolean secret(String name, Object value) {
        return Secrets.sensitive(name) && !Secrets.counted(name, value);
    }

    /**
     * Whether an argument's expression names a secret, by its last name: `user.password`, `getApiKey()`,
     * `headers["Authorization"]`, `env.get("API_TOKEN")`; not `tokens.size()` nor `tokenCount` (a number about the
     * secret), whose last name says otherwise. A string's template is read for the names it puts in: "Bearer $token".
     */
    static boolean secretExpr(String expr, Object value) {
        String text = expr.strip();
        if (!text.isEmpty() && (text.charAt(0) == '"' || text.charAt(0) == '\'')) {
            Matcher template = TEMPLATE.matcher(text);
            while (template.find()) {
                String name = lastName(template.group(1) != null ? template.group(1) : template.group(2));
                if (name != null && Secrets.sensitive(name)) return true;
            }
            return false;
        }
        String name = lastName(text);
        return name != null && secret(name, value);
    }

    /** A Kotlin template's names: $token, ${user.password}. */
    private static final Pattern TEMPLATE = Pattern.compile("\\$(?:\\{([^}]*)}|([A-Za-z_][\\w.]*))");
    /** A lookup by a key written as a string: its key is the name (map.get("token"), getenv("API_KEY")). */
    private static final Pattern KEYED = Pattern.compile(
            "(?:get|getOrDefault|getOrElse|getOrNull|getProperty|getenv|getHeader|getParameter|getString|optString|opt|"
                    + "getValue|getAttribute|getFirst|header|property|remove)");

    /** Calls whose value is the receiver's, or one of its elements: the receiver's name is the one that tells. */
    private static final Set<String> SAME = Set.of(
            "get", "getOrNull", "getOrElse", "getOrDefault", "getValue", "first", "last", "firstOrNull", "lastOrNull",
            "single", "elementAt", "orElse", "orElseThrow", "orElseGet", "join", "await", "block", "peek", "poll",
            "toString", "trim", "strip", "lowercase", "uppercase", "toLowerCase", "toUpperCase", "orEmpty", "toCharArray",
            "getBytes", "encodeToByteArray", "toByteArray", "clone", "copy");

    /** The last name an expression reads: past calls' parentheses, ?. and !!; a key written as a string. */
    static String lastName(String expr) {
        String text = expr.strip();
        while (true) {
            while (text.endsWith("!!") || text.endsWith("?")) text = text.substring(0, text.length() - (text.endsWith("!!") ? 2 : 1)).strip();
            if (text.isEmpty()) return null;
            char last = text.charAt(text.length() - 1);
            if (last == ')' || last == ']') {
                int open = opening(text, text.length() - 1);
                if (open < 0) return null;
                String inside = text.substring(open + 1, text.length() - 1).strip();
                String before = text.substring(0, open).strip();
                String key = stringKey(inside);
                if (last == ']') {
                    if (key != null) return key;
                    text = before;
                    continue;
                }
                String call = trailingName(before);
                if (call != null && key != null && KEYED.matcher(call).matches()) return key;
                if (call != null && SAME.contains(call) && before.length() > call.length()) {
                    // The receiver's own value, or one of its elements: tokens.get(0), password.trim(), secret.get().
                    String receiver = before.substring(0, before.length() - call.length()).strip();
                    if (receiver.endsWith(".")) {
                        text = receiver.substring(0, receiver.length() - 1);
                        continue;
                    }
                }
                if (call != null) return call;
                // A parenthesized expression, a cast: its own last name.
                text = inside;
                continue;
            }
            return trailingName(text);
        }
    }

    /** The string a call's first argument (or an index) is written as: "token" in get("token"), else null. */
    private static String stringKey(String inside) {
        if (inside.length() < 2 || inside.charAt(0) != '"') return null;
        int end = inside.indexOf('"', 1);
        if (end < 0) return null;
        String rest = inside.substring(end + 1).strip();
        return rest.isEmpty() || rest.startsWith(",") ? inside.substring(1, end) : null;
    }

    /** The identifier the text ends in, or null. */
    private static String trailingName(String text) {
        int end = text.length();
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) start--;
        if (start == end || !Character.isJavaIdentifierStart(text.charAt(start))) return null;
        return text.substring(start, end);
    }

    /** Where the bracket that `close` closes opens, past strings; -1 when it doesn't. */
    private static int opening(String text, int close) {
        int depth = 0;
        char quote = 0;
        for (int i = close; i >= 0; i--) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote && (i == 0 || text.charAt(i - 1) != '\\')) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (--depth == 0) return i;
            }
        }
        return -1;
    }

    /** A `name=value` pair in a text a class writes itself (a Kotlin data class, a toString): the name, then its value. */
    private static final Pattern PAIR = Pattern.compile("(?<![\\w.$])([A-Za-z_][\\w-]*)\\s*=\\s*");

    /**
     * A class's own text with the values of its secret-named pairs masked: Cart(items=3, token=abc) is
     * Cart(items=3, token=********). A value runs to the next `, name=` or the bracket that closes its pair's.
     */
    static String maskPairs(String text) {
        if (text.indexOf('=') < 0) return text;
        StringBuilder out = null;
        int copied = 0;
        Matcher pair = PAIR.matcher(text);
        int from = 0;
        while (from < text.length() && pair.find(from)) {
            int end = valueEnd(text, pair.end());
            String value = text.substring(pair.end(), end);
            if (!value.equals(Secrets.MASK) && secret(pair.group(1), number(value))) {
                if (out == null) out = new StringBuilder(text.length());
                out.append(text, copied, pair.end()).append(Secrets.MASK);
                copied = end;
                from = end;
            } else {
                from = pair.end();
            }
        }
        if (out == null) return text;
        return out.append(text, copied, text.length()).toString();
    }

    /** Where a pair's value ends: before `, name=` at its own depth, or the bracket closing its pair's group. */
    private static int valueEnd(String text, int start) {
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) return i;
                depth--;
            } else if (c == ',' && depth == 0) {
                Matcher next = PAIR.matcher(text);
                int at = i + 1;
                while (at < text.length() && text.charAt(at) == ' ') at++;
                next.region(at, text.length());
                if (next.lookingAt()) return i;
            }
        }
        return text.length();
    }

    /** A pair's value as a number when it is one: `tokens=1024` is a model's count. */
    private static Object number(String value) {
        try {
            return Double.parseDouble(value.strip());
        } catch (NumberFormatException e) {
            return value;
        }
    }

    private static void more(List<Node> items, int size) {
        if (size > items.size()) items.add(Node.leaf("… " + (size - items.size()) + " more"));
    }

    /** A map's entries, by their keys when they compare, else as the map has them. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Map.Entry<?, ?>> sorted(Map<?, ?> map) {
        List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
        try {
            entries.sort((a, b) -> ((Comparable) a.getKey()).compareTo(b.getKey()));
        } catch (RuntimeException e) {
            return new ArrayList<>(map.entrySet());
        }
        return entries;
    }

    private static boolean jdk(Class<?> type) {
        String name = type.getName();
        for (String prefix : JDK) if (name.startsWith(prefix)) return true;
        return false;
    }

    /** Whether the class, or one of its superclasses short of Object, writes its own toString (a Kotlin data class). */
    private static boolean ownToString(Class<?> type) {
        for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
            try {
                at.getDeclaredMethod("toString");
                return true;
            } catch (NoSuchMethodException e) {
                // Its superclass's, then.
            } catch (RuntimeException | LinkageError e) {
                return true;
            }
        }
        return false;
    }

    /** A value's own text, its secret-named pairs masked (maskPairs). */
    private static String text(Object value) {
        try {
            if (value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) return String.valueOf(value);
            return maskPairs(String.valueOf(value));
        } catch (RuntimeException | LinkageError e) {
            return "<toString failed: " + e.getClass().getSimpleName() + ">";
        }
    }

    private static String quoted(String text, char quote) {
        StringBuilder out = new StringBuilder(text.length() + 2).append(quote);
        for (int i = 0; i < text.length() && out.length() <= MAX_REPR; i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\\' -> out.append("\\\\");
                default -> {
                    if (c == quote) out.append('\\').append(c);
                    else if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append(quote).toString();
    }
}
