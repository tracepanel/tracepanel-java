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
        if (context.isEmpty() || line <= 0) {
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
