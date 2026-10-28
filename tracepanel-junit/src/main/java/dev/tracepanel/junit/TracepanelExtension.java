package dev.tracepanel.junit;

import dev.tracepanel.core.Stacks;
import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.extension.DynamicTestInvocationContext;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/**
 * Tracepanel's JUnit extension: each test a trace, and what the agent finds in it (an N+1, duplicate queries) a
 * failure.
 *
 *     @Tracepanel                                   // on the class: its tests; or JUnit's extension autodetection
 *     class FeedTest {
 *         @Test
 *         @Tracepanel(maxQueries = 10, allow = "n+1")
 *         void feed(TestTrace trace) throws Exception {
 *             mvc.perform(get("/feed")).andExpect(status().isOk());
 *             assertThat(trace.queries()).isLessThanOrEqualTo(10);
 *             maxQueries(2, () -> mvc.perform(get("/feed?page=2")));
 *         }
 *     }
 *
 * Registered (by @Tracepanel, @ExtendWith, or junit.jupiter.extensions.autodetection.enabled=true with this jar on
 * the test classpath), it turns Tracepanel on in the test JVM as development does, with the widget off, and starts
 * the project's agent unless one answers. Each test's body is then a trace of kind `test`; what runs outside it (a
 * @BeforeEach, the app's startup) is not sent at all. A request the test makes of the app (MockMvc, TestRestTemplate,
 * WebTestClient, Ktor's testApplication client) is a call of the test's trace, and the app's trace of it is linked
 * to the test by its W3C trace id. Once the test has passed, the agent is asked what it found in the test's trace and
 * in its requests': a finding of a kind it fails on fails the test, with the finding, its query, where the code made
 * it and a link to the trace in the console. Without an agent the tests run as usual, and a single warning says that
 * findings are not checked: counting queries needs no agent.
 *
 * The kinds that fail a test are the configuration parameter tracepanel.failOn (junit-platform.properties, or a
 * system property): "n+1, duplicates" by default.
 */
public final class TracepanelExtension implements InvocationInterceptor, ParameterResolver {
    /** The configuration parameter that names the kinds of issues that fail a test. */
    public static final String FAIL_ON = "tracepanel.failOn";
    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(TracepanelExtension.class);
    private static final String ANNOTATION = "@" + Tracepanel.class.getSimpleName();

    static {
        Session.install();
    }

    public TracepanelExtension() {
        // JUnit makes it: from the annotation, @ExtendWith, or the ServiceLoader.
    }

    @Override
    public void interceptTestMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        traced(invocation, extensionContext);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation, ReflectiveInvocationContext<Method> invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        traced(invocation, extensionContext);
    }

    @Override
    public void interceptDynamicTest(Invocation<Void> invocation, DynamicTestInvocationContext invocationContext,
            ExtensionContext extensionContext) throws Throwable {
        traced(invocation, extensionContext);
    }

    /** The test's body as its trace; then what it did against its rules. */
    private static void traced(Invocation<Void> invocation, ExtensionContext context) throws Throwable {
        if (Session.off()) {
            invocation.proceed();
            return;
        }
        Rules rules;
        try {
            Set<String> failOn = Session.INSTANCE.prepare(context.getConfigurationParameter(FAIL_ON).orElse(null));
            rules = rules(context, failOn);
        } catch (ExtensionConfigurationException e) {
            // The test's own rules, or the run's setting: its failure, not a run's crash.
            invocation.skip();
            throw e;
        }
        dev.tracepanel.core.Tracepanel tracepanel = Session.tracepanel();
        TestTrace test = test(context);
        test.start(tracepanel);
        try {
            invocation.proceed();
        } catch (Throwable error) {
            test.stop(tracepanel, error instanceof TestAbortedException ? null : error);
            throw error;
        }
        test.stop(tracepanel, null);
        List<String> problems = problems(test, rules);
        if (!problems.isEmpty()) throw failure(String.join("\n\n", problems));
    }

    // ---------- the test's rules ----------

    /** What fails a test: more queries than `maxQueries`, or an issue the agent found of a kind in `failOn`. */
    record Rules(Set<String> failOn, Integer maxQueries) {}

    /** The test's @Tracepanel, the nearest first (the method's, then its class's and the classes around it), over the run's. */
    static Rules rules(ExtensionContext context, Set<String> runFailOn) {
        String[] failOn = null;
        String[] allow = null;
        Integer limit = null;
        for (ExtensionContext at = context; at != null; at = at.getParent().orElse(null)) {
            Optional<AnnotatedElement> element = at.getElement();
            if (element.isEmpty()) continue;
            Optional<Tracepanel> found = AnnotationSupport.findAnnotation(element.get(), Tracepanel.class);
            if (found.isEmpty()) continue;
            Tracepanel annotation = found.get();
            if (failOn == null && !inherited(annotation.failOn())) failOn = annotation.failOn();
            if (allow == null && !inherited(annotation.allow())) allow = annotation.allow();
            if (limit == null && annotation.maxQueries() != -1) limit = annotation.maxQueries();
        }
        if (limit != null && limit < 0) {
            throw new ExtensionConfigurationException("maxQueries must be a whole number, got " + limit);
        }
        Set<String> failing = failOn != null ? Kinds.of(failOn, "failOn") : new HashSet<>(runFailOn);
        Set<String> allowed = Kinds.of(allow != null ? allow : new String[0], "allow");
        Set<String> rest = new HashSet<>(failing);
        rest.removeAll(allowed);
        return new Rules(rest, limit);
    }

    private static boolean inherited(String[] kinds) {
        return kinds.length == 1 && Tracepanel.INHERITED.equals(kinds[0]);
    }

    /** What the test did against its rules: a failure's message each, none when it held to them. */
    static List<String> problems(TestTrace test, Rules rules) {
        List<String> found = new ArrayList<>();
        if (rules.maxQueries() != null) {
            List<Query> made = test.sql();
            if (made.size() > rules.maxQueries()) found.add(Query.tooMany(made, rules.maxQueries()));
        }
        if (!rules.failOn().isEmpty() && test.console() != null) {
            List<Finding> failing = test.console().findings(test.traces()).stream()
                    .filter(finding -> rules.failOn().contains(finding.kind())).toList();
            if (!failing.isEmpty()) {
                List<String> listed = new ArrayList<>();
                for (Finding finding : failing) listed.add(finding.describe(test.id()));
                Set<String> kinds = new TreeSet<>();
                for (Finding finding : failing) kinds.add('"' + finding.kind() + '"');
                String allow = kinds.size() == 1 ? kinds.iterator().next() : "{" + String.join(", ", kinds) + "}";
                found.add("tracepanel found " + failing.size() + (failing.size() == 1 ? " issue" : " issues") + " in this test:\n\n"
                        + String.join("\n\n", listed) + "\n\nThe test's trace: " + test.url() + "\n"
                        + "Allowed here with " + ANNOTATION + "(allow = " + allow + ").");
            }
        }
        return found;
    }

    /** An assertion's failure as JUnit shows it: the message, without this extension's frames. */
    private static AssertionFailedError failure(String message) {
        AssertionFailedError error = new AssertionFailedError(message);
        error.setStackTrace(new StackTraceElement[0]);
        return error;
    }

    // ---------- the test's trace ----------

    /** The test's trace-to-be: the one a parameter hands the test, and the one its body records. */
    private static TestTrace test(ExtensionContext context) {
        try {
            // The agent, before the trace takes its console: a parameter is resolved before the body runs.
            Session.INSTANCE.prepare(context.getConfigurationParameter(FAIL_ON).orElse(null));
        } catch (ExtensionConfigurationException e) {
            // A misspelled setting: the test fails with it as its body would run.
        }
        return context.getStore(NAMESPACE).getOrComputeIfAbsent(TestTrace.class, key -> {
            String className = context.getRequiredTestClass().getName();
            List<String> names = new ArrayList<>();
            for (ExtensionContext at = context; at != null && !(at.getElement().orElse(null) instanceof Class<?>);
                    at = at.getParent().orElse(null)) {
                names.add(0, at.getDisplayName());
            }
            String name = className + (names.isEmpty() ? "" : " > " + String.join(" > ", names));
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("test.case.name", name);
            Stacks stacks = Session.tracepanel().stacks();
            String file = source(stacks, context.getRequiredTestClass());
            attrs.put("code.filepath", file);
            String function = context.getTestMethod().map(Method::getName).orElse(context.getDisplayName());
            Integer line = context.getTestMethod().map(method -> line(file, method.getName())).orElse(null);
            if (line != null) attrs.put("code.lineno", line);
            attrs.put("code.function", function);
            return new TestTrace(name, attrs, Session.INSTANCE.console());
        }, TestTrace.class);
    }

    /** The test class's source file, relative to the project when it is there (a Kotlin one, or a Java one). */
    private static String source(Stacks stacks, Class<?> type) {
        Class<?> top = type;
        while (top.getEnclosingClass() != null) top = top.getEnclosingClass();
        String java = stacks.path(top.getName(), top.getSimpleName() + ".java");
        if (java.startsWith("src/")) return java;
        String kotlin = stacks.path(top.getName(), top.getSimpleName() + ".kt");
        return kotlin.startsWith("src/") ? kotlin : java;
    }

    /** The line that declares the test's method in its source file, or null when it can't be read. */
    private static Integer line(String file, String method) {
        Pattern declared = Pattern.compile("(\\bvoid\\s+|\\bfun\\s+)(" + Pattern.quote(method) + "|`" + Pattern.quote(method) + "`)\\s*\\(");
        try {
            Path path = Path.of(System.getenv().getOrDefault("TRACEPANEL_ROOT", System.getProperty("user.dir"))).resolve(file);
            if (!Files.isRegularFile(path)) return null;
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) if (declared.matcher(lines.get(i)).find()) return i + 1;
        } catch (IOException | RuntimeException e) {
            // A source it can't read: no line.
        }
        return null;
    }

    // ---------- what a test reads ----------

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == TestTrace.class && !Session.off();
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return test(extensionContext);
    }

    /**
     * The current test's trace: its queries so far, what the agent found, its link in the console. A test method can
     * take it as a parameter too.
     */
    public static TestTrace testTrace() {
        TestTrace test = Session.INSTANCE.current();
        if (test == null) {
            throw new IllegalStateException("testTrace() needs a test's trace, and none is running: register "
                    + "TracepanelExtension (@Tracepanel on the test class, or JUnit's extension autodetection)");
        }
        return test;
    }

    /**
     * Fails (an AssertionFailedError) when `block` makes more than `limit` SQL queries, those of the requests it makes
     * of the app included; else returns what it returned.
     *
     *     var page = maxQueries(3, () -> mvc.perform(get("/posts")).andReturn());
     *
     * It counts in the current test's trace.
     */
    public static <T> T maxQueries(int limit, ThrowingSupplier<T> block) {
        TestTrace test = Session.INSTANCE.current();
        if (test == null) {
            throw new IllegalStateException("maxQueries() counts the queries of a test's trace, and none is running: "
                    + "register TracepanelExtension (@Tracepanel on the test class, or JUnit's extension autodetection)");
        }
        Set<Object> before = new HashSet<>();
        for (Map<String, Object> span : test.sqlSpans()) before.add(span.get("id"));
        T result;
        try {
            result = block.get();
        } catch (Throwable error) {
            throw sneaky(error);
        }
        List<Query> made = test.sqlSpans().stream().filter(span -> !before.contains(span.get("id"))).map(Query::of).toList();
        if (made.size() > limit) throw failure(Query.tooMany(made, limit));
        return result;
    }

    /** The same, for a block that returns nothing. */
    public static void maxQueries(int limit, Executable block) {
        maxQueries(limit, () -> {
            block.execute();
            return null;
        });
    }

    /** The block's own exception, as it threw it, checked or not. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable error) throws E {
        throw (E) error;
    }
}
