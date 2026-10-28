package dev.tracepanel.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * The app's code in a stack: where a query or a log came from ("com/shop/BookService.java:42 in list"), and an
 * error's frames, oldest first, with the source around each of the app's lines.
 */
public final class Stacks {
    /** The JVM's, the frameworks' and the drivers': never the app's code, whatever the app's packages. */
    private static final List<String> LIBRARIES = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "scala.", "groovy.",
            "org.springframework.", "org.hibernate.", "com.zaxxer.", "org.postgresql.", "com.mysql.", "org.mariadb.",
            "org.h2.", "org.hsqldb.", "org.apache.", "org.eclipse.", "io.netty.", "reactor.", "io.micrometer.",
            "ch.qos.logback.", "org.slf4j.", "org.thymeleaf.", "com.fasterxml.", "org.jboss.", "net.bytebuddy.",
            "org.junit.", "dev.tracepanel.");
    /**
     * What makes a query rather than asks for one: the JVM's plumbing, the ORMs and their repositories, the pools and the
     * drivers, Tracepanel itself. Past these is the library code that wanted the rows (Jackson writing a lazy relation, a
     * template, Spring Security loading a user).
     */
    private static final List<String> QUERY_LAYERS = List.of(
            "java.", "javax.", "jakarta.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "dev.tracepanel.",
            "net.bytebuddy.", "org.hibernate.", "org.springframework.jdbc.", "org.springframework.orm.",
            "org.springframework.dao.", "org.springframework.transaction.", "org.springframework.aop.",
            "org.springframework.cglib.", "org.springframework.data.jpa.", "org.springframework.data.jdbc.",
            "org.springframework.data.relational.", "org.springframework.data.repository.",
            "org.springframework.data.projection.", "org.springframework.data.util.", "org.jetbrains.exposed.",
            "org.jooq.", "org.apache.ibatis.", "org.mybatis.", "com.zaxxer.", "org.apache.tomcat.jdbc.",
            "com.mchange.", "org.postgresql.", "com.mysql.", "org.mariadb.", "org.h2.", "org.hsqldb.", "oracle.jdbc.",
            "com.microsoft.sqlserver.", "org.sqlite.", "org.apache.derby.", "com.ibm.db2.", "net.sourceforge.jtds.");
    /** The ORMs the agent knows the N+1 fix of, by their frames' package: what a query's db.client names. */
    private static final Map<String, String> ORMS = Map.of("org.hibernate.", "hibernate", "org.jetbrains.exposed.", "exposed");
    private static final String[] SOURCE_ROOTS = {"src/main/java", "src/main/kotlin", "src/main/groovy", "src/test/java"};
    private static final int CONTEXT_LINES = 5;

    private final Path root;
    private final List<String> appPackages;
    private final Map<String, Optional<Path>> sources = new ConcurrentHashMap<>();

    /** `appPackages`: the app's own (com.shop); empty, any class outside the libraries above is the app's. */
    public Stacks(Path root, List<String> appPackages) {
        this.root = root;
        this.appPackages = List.copyOf(appPackages);
    }

    public boolean inApp(String className) {
        for (String library : LIBRARIES) if (className.startsWith(library)) return false;
        if (generated(className)) return false;
        if (appPackages.isEmpty()) return true;
        for (String pkg : appPackages) if (className.equals(pkg) || className.startsWith(pkg + ".")) return true;
        return false;
    }

    /**
     * Classes made at runtime in the app's packages: Hibernate's lazy proxies (Author$HibernateProxy$…), Spring's
     * CGLIB subclasses (…$$SpringCGLIB$$0), ByteBuddy's. Their frames have no line of the app's to point at.
     */
    private static boolean generated(String className) {
        return className.contains("$$") || className.contains("$HibernateProxy$") || className.contains("$ByteBuddy$");
    }

    /** The app's frame nearest to the call being made now, as "path:line in method", or null when none is the app's. */
    public String origin() {
        return StackWalker.getInstance()
                .walk(frames -> frames.filter(frame -> inApp(frame.getClassName()) && frame.getLineNumber() > 0).findFirst())
                .map(frame -> where(frame.getClassName(), frame.getFileName(), frame.getLineNumber(), frame.getMethodName()))
                .orElse(null);
    }

    /**
     * For a query no code of the app's made (code.origin is null): the library code that asked for it, the first frame
     * past the query layers, as "path:line in method"; null when there is none.
     */
    public String caller() {
        return StackWalker.getInstance()
                .walk(frames -> frames.filter(frame -> !queryLayer(frame.getClassName()) && !generated(frame.getClassName())
                                && frame.getLineNumber() > 0)
                        .findFirst())
                .map(frame -> where(frame.getClassName(), frame.getFileName(), frame.getLineNumber(), frame.getMethodName()))
                .orElse(null);
    }

    /**
     * The ORM that makes the query being made now, from the query layers on top of the stack: "hibernate" (JPA and
     * Spring Data JPA run on it), "exposed", or "jdbc" for any other (jOOQ, MyBatis, JdbcTemplate, plain JDBC).
     */
    public String client() {
        return StackWalker.getInstance().walk(frames -> client(frames.map(StackWalker.StackFrame::getClassName)));
    }

    /** The ORM of a stack's classes, the newest call first; past the query layers is the code that asked for the rows. */
    static String client(Stream<String> classNames) {
        return classNames.takeWhile(name -> queryLayer(name) || generated(name))
                .map(Stacks::orm)
                .filter(orm -> orm != null)
                .findFirst()
                .orElse("jdbc");
    }

    private static String orm(String className) {
        if (className.startsWith("org.hibernate.validator.")) return null;
        for (Map.Entry<String, String> orm : ORMS.entrySet()) if (className.startsWith(orm.getKey())) return orm.getValue();
        return null;
    }

    static boolean queryLayer(String className) {
        for (String layer : QUERY_LAYERS) if (className.startsWith(layer)) return true;
        return false;
    }

    /** The app's frame nearest to the call, by a predicate on the class too (a log's own logger frames skipped). */
    public StackWalker.StackFrame nearest(Predicate<String> skip) {
        return StackWalker.getInstance()
                .walk(frames -> frames.filter(frame -> !skip.test(frame.getClassName()) && inApp(frame.getClassName())
                                && frame.getLineNumber() > 0)
                        .findFirst())
                .orElse(null);
    }

    public String where(String className, String fileName, int line, String method) {
        return path(className, fileName) + ":" + line + " in " + method;
    }

    /** A class's source file, relative to the project when it is there, else as its package names it. */
    public String path(String className, String fileName) {
        String packagePath = packagePath(className, fileName);
        return source(packagePath).map(found -> root.relativize(found).toString().replace('\\', '/')).orElse(packagePath);
    }

    /** An error's frames, oldest call first, the app's with their source. */
    public List<Map<String, Object>> frames(Throwable error) {
        StackTraceElement[] stack = error.getStackTrace();
        List<Map<String, Object>> frames = new ArrayList<>(stack.length);
        for (int i = stack.length - 1; i >= 0; i--) frames.add(frame(stack[i]));
        return frames;
    }

    private Map<String, Object> frame(StackTraceElement element) {
        boolean app = inApp(element.getClassName());
        Map<String, Object> frame = new LinkedHashMap<>();
        String packagePath = packagePath(element.getClassName(), element.getFileName());
        Optional<Path> file = app ? source(packagePath) : Optional.empty();
        frame.put("filename", file.map(found -> root.relativize(found).toString().replace('\\', '/')).orElse(packagePath));
        frame.put("module", element.getClassName());
        frame.put("function", element.getMethodName());
        frame.put("lineno", Math.max(element.getLineNumber(), 0));
        frame.put("in_app", app);
        List<String> context = file.map(found -> lines(found)).orElse(List.of());
        int line = element.getLineNumber();
        // A Kotlin inline function's body is mapped to lines past the end of the file that calls it: no source there.
        if (context.isEmpty() || line <= 0 || line > context.size()) {
            frame.put("context_start", null);
            frame.put("context", List.of());
        } else {
            int from = Math.max(1, line - CONTEXT_LINES);
            int to = Math.min(context.size(), line + CONTEXT_LINES);
            frame.put("context_start", from);
            frame.put("context", new ArrayList<>(context.subList(from - 1, to)));
        }
        // The JVM keeps no locals a running program can read.
        frame.put("locals", null);
        return frame;
    }

    private static String packagePath(String className, String fileName) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot).replace('.', '/') + "/";
        String file = fileName != null ? fileName : className.substring(dot + 1).replaceAll("\\$.*", "") + ".java";
        return pkg + file;
    }

    private Optional<Path> source(String packagePath) {
        return sources.computeIfAbsent(packagePath, path -> {
            for (String sourceRoot : SOURCE_ROOTS) {
                Path candidate = root.resolve(sourceRoot).resolve(path);
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            }
            return Optional.empty();
        });
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }
}
