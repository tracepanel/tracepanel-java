package dev.tracepanel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tracepanel.dump: printed as python-devtools prints, and a "dump" log record of the current trace. */
class DumpsTest {
    private static final String FILE = "src/test/java/dev/tracepanel/core/DumpsTest.java";

    private record Order(int id, BigDecimal total, List<String> items) {}

    /** A class of the app's without a toString: its fields. */
    static class Link {
        final String name;
        Link next;

        Link(String name) {
            this.name = name;
        }
    }

    static class Shipped extends Link {
        final LocalDate on;

        Shipped(String name, LocalDate on) {
            super(name);
            this.on = on;
        }
    }

    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream before;

    @BeforeEach
    void capture() {
        before = System.err;
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void release() {
        System.setErr(before);
        Tracepanel.bind(null);
    }

    private String printed() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private static int nextLine() {
        return StackWalker.getInstance().walk(frames -> frames.skip(1).findFirst()).orElseThrow().getLineNumber() + 1;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> logs(Trace trace) {
        return (List<Map<String, Object>>) trace.finish().get("logs");
    }

    private static Map<String, Object> arg(String expr, String repr, String type, Integer len) {
        Map<String, Object> arg = new LinkedHashMap<>();
        arg.put("expr", expr);
        arg.put("repr", repr);
        arg.put("type", type);
        arg.put("len", len);
        return arg;
    }

    private static Map<String, Object> record(String spanId, String message, Map<String, Object> attrs) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("span_id", spanId);
        log.put("level", "debug");
        log.put("logger", "tracepanel");
        log.put("message", message);
        log.put("exception", null);
        log.put("attrs", attrs);
        return log;
    }

    private static Map<String, Object> attrs(List<Map<String, Object>> args, String origin) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("log.kind", "dump");
        attrs.put("dump.lang", "java");
        attrs.put("dump.args", args);
        attrs.put("code.origin", origin);
        return attrs;
    }

    @Test
    void a_dump_in_a_trace_prints_and_is_a_debug_record_on_the_current_span() {
        Trace trace = Traces.request("GET /orders/7");
        Tracepanel.bind(trace);
        String span = trace.currentSpan();
        Order order = new Order(7, new BigDecimal("12.50"), List.of("pen", "ink"));
        List<Integer> items = new ArrayList<>(List.of(1, 2, 3));
        Map<String, Integer> prices = new HashMap<>(Map.of("pen", 2, "ink", 3));

        int line = nextLine();
        Tracepanel.dump(order, items, prices, "here");

        List<Map<String, Object>> logs = logs(trace);
        String method = "a_dump_in_a_trace_prints_and_is_a_debug_record_on_the_current_span";
        String message = FILE + ":" + line + " " + method + "\n"
                + "    order: Order[id=7, total=12.50, items=[\"pen\", \"ink\"]] (Order)\n"
                + "    items: [1, 2, 3] (ArrayList) len=3\n"
                + "    prices: {\"ink\"=3, \"pen\"=2} (HashMap) len=2\n"
                + "    \"here\" (String) len=4";

        assertEquals(message + System.lineSeparator(), printed());

        assertEquals(1, logs.size());
        Map<String, Object> log = new LinkedHashMap<>(logs.get(0));
        assertInstanceOf(Long.class, log.remove("time"));
        assertEquals(record(span, message, attrs(List.of(
                arg("order", "Order[id=7, total=12.50, items=[\"pen\", \"ink\"]]", "Order", null),
                arg("items", "[1, 2, 3]", "ArrayList", 3),
                arg("prices", "{\"ink\"=3, \"pen\"=2}", "HashMap", 2),
                arg(null, "\"here\"", "String", 4)), FILE + ":" + line + " in " + method)), log);
    }

    @Test
    void a_call_over_lines_reads_each_argument_as_written() {
        Trace trace = Traces.request("GET /");
        Tracepanel.bind(trace);
        List<String> names = List.of("a", "b");

        Tracepanel.dump(
                names.size(),
                names.stream().map(name -> name + ", x").toList(),
                Map.of("k", "v(").get("k"));

        List<?> args = (List<?>) dumpArgs(logs(trace).get(0));

        assertEquals(List.of("names.size()", "names.stream().map(name -> name + \", x\").toList()",
                "Map.of(\"k\", \"v(\").get(\"k\")"), args.stream().map(arg -> ((Map<?, ?>) arg).get("expr")).toList());
    }

    private static Object dumpArgs(Map<String, Object> log) {
        return ((Map<?, ?>) log.get("attrs")).get("dump.args");
    }

    @Test
    void a_value_goes_over_lines_when_it_doesn_t_fit_on_one_with_cycles_and_depth_cut() {
        Link a = new Link("a");
        a.next = a;
        Shipped shipped = new Shipped("parcel", LocalDate.of(2026, 10, 31));
        List<Object> deep = new ArrayList<>(List.of(1));
        for (int i = 0; i < 8; i++) deep = new ArrayList<>(List.of(deep));
        Map<String, Object> wide = new LinkedHashMap<>();
        wide.put("customer", "Ann Example of Somewhere Far");
        wide.put("lines", List.of(new Order(1, BigDecimal.ONE, List.of("a")), new Order(2, BigDecimal.TEN, List.of())));
        wide.put("note", Optional.of("gift"));

        assertEquals("Link(name=\"a\", next=<cycle: Link>)", Dumps.repr(a));
        assertEquals("Shipped(name=\"parcel\", next=null, on=2026-10-31)", Dumps.repr(shipped));
        assertEquals("[[[[[[[…]]]]]]]", Dumps.repr(deep));
        assertEquals("""
                {
                    "customer"="Ann Example of Somewhere Far",
                    "lines"=[
                        Order[id=1, total=1, items=["a"]],
                        Order[id=2, total=10, items=[]]
                    ],
                    "note"=Optional["gift"]
                }""", Dumps.repr(wide));
        assertEquals("[1, 2]", Dumps.repr(new int[] {1, 2}));
        assertEquals(2, Dumps.length(new int[] {1, 2}));
        assertEquals("int[]", Dumps.typeName(int[].class));
        assertEquals("'c'", Dumps.repr('c'));
        assertEquals("\"a\\\"b\\n\"", Dumps.repr("a\"b\n"));
        // The JDK's own classes are what their toString says.
        assertEquals("2026-10-31", Dumps.repr(LocalDate.of(2026, 10, 31)));
    }

    @Test
    void a_value_under_a_secret_s_name_is_masked_in_the_record_and_the_print() {
        Trace trace = Traces.request("POST /login");
        Tracepanel.bind(trace);
        String password = "hunter2";
        String user = "ann";

        Tracepanel.dump(user, password);

        Map<String, Object> log = logs(trace).get(0);

        assertEquals(List.of(arg("user", "\"ann\"", "String", 3), arg("password", Secrets.MASK, "String", null)),
                dumpArgs(log));
        assertEquals(true, printed().contains("\n    password: ******** (String)\n"));
        assertEquals(false, printed().contains("hunter2"));
    }

    @Test
    void outside_a_trace_a_dump_prints_and_records_nothing() {
        Trace trace = Traces.request("GET /");
        Tracepanel.bind(null);
        List<String> tags = List.of("new");

        int line = nextLine();
        Tracepanel.dump(tags);

        assertEquals(FILE + ":" + line + " outside_a_trace_a_dump_prints_and_records_nothing\n"
                + "    tags: [\"new\"] (List12) len=1" + System.lineSeparator(), printed());
        assertEquals(List.of(), logs(trace));
    }

    @Test
    void a_long_value_is_cut_and_says_so() {
        Trace trace = Traces.request("GET /");
        Tracepanel.bind(trace);
        String text = "x".repeat(20_000);

        Tracepanel.dump(text);

        List<?> args = (List<?>) dumpArgs(logs(trace).get(0));
        String repr = "\"" + "x".repeat(Dumps.MAX_REPR - 1) + "… (truncated)";

        assertEquals(List.of(arg("text", repr, "String", 20_000)), args);
    }

    @Test
    void without_the_caller_s_source_each_expression_is_null() {
        Dumps.Dump dump = Dumps.build(new Object[] {5, List.of()}, new Frame("elsewhere.Gone", "Gone.java", 12, "run"));

        assertEquals("elsewhere/Gone.java:12 run\n    5 (Integer)\n    [] (ListN) len=0", dump.message());
        assertEquals(attrs(List.of(arg(null, "5", "Integer", null), arg(null, "[]", "ListN", 0)),
                "elsewhere/Gone.java:12 in run"), dump.attrs());
    }

    @Test
    void a_call_s_arguments_are_split_at_its_own_commas_only() {
        List<String> java = List.of("        Tracepanel.dump(a, f(\"x, y\"), m.get('c'), list.stream().map((v, w) -> v + w), /* c, */ 42);");
        List<String> kotlin = List.of("    dump(cart, \"total: ${cart.items.joinToString(\", \")}\", items.map { it.id }, \"\"\"raw, \"\"\")");

        assertEquals(Arrays.asList("a", "f(\"x, y\")", "m.get('c')", "list.stream().map((v, w) -> v + w)", null),
                Dumps.exprs(java, 1, 5));
        assertEquals(Arrays.asList("cart", "\"total: ${cart.items.joinToString(\", \")}\"", "items.map { it.id }", null),
                Dumps.exprs(kotlin, 1, 4));
        // A count that doesn't match the values (a spread array), or no call on the line: nothing is guessed.
        assertEquals(null, Dumps.exprs(java, 1, 3));
        assertEquals(null, Dumps.exprs(List.of("int x = 1;"), 1, 1));
        assertEquals(null, Dumps.exprs(List.of("dump(a,"), 1, 1));
    }

    /** A frame of a class whose source isn't in the project. */
    private record Frame(String getClassName, String getFileName, int getLineNumber, String getMethodName)
            implements StackWalker.StackFrame {
        @Override
        public Class<?> getDeclaringClass() {
            return Object.class;
        }

        @Override
        public MethodType getMethodType() {
            return MethodType.methodType(void.class);
        }

        @Override
        public String getDescriptor() {
            return "()V";
        }

        @Override
        public int getByteCodeIndex() {
            return 0;
        }

        @Override
        public boolean isNativeMethod() {
            return false;
        }

        @Override
        public StackTraceElement toStackTraceElement() {
            return new StackTraceElement(getClassName, getMethodName, getFileName, getLineNumber);
        }
    }
}
