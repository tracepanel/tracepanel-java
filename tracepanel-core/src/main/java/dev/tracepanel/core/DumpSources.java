package dev.tracepanel.core;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Where a dump call's source is in a dev build, to read its arguments' expressions: under any module of the build the
 * project is in. The build's root is the nearest directory up with a settings.gradle(.kts), else the topmost of the
 * pom.xml directories above one another; its modules are its Gradle includes and Maven modules at any depth, the
 * directories with a src of their own below it, and the module of each class directory on the classpath
 * (build/classes/..., target/classes, out/production/...), the caller's own first. Each module's sources are every
 * src/&lt;set&gt;/&lt;language&gt; (main, test, commonMain, jvmMain...), indexed once by file name: a file is the one whose path
 * ends in the class's package path, else the one whose package declaration names it (Kotlin's files need not sit in
 * their package's directory).
 */
final class DumpSources {
    private static final List<String> LANGUAGES = List.of("java", "kotlin", "groovy", "scala");
    /** What is never a module's source: builds, tools' state, dependencies. */
    private static final Set<String> SKIPPED = Set.of(
            "build", "target", "out", "bin", "node_modules", "dist", ".git", ".gradle", ".idea", ".mvn", ".tracepanel",
            ".kotlin", ".venv");
    /** How far up a build's root is looked for, and how deep below it modules are. */
    private static final int MAX_UP = 12;
    private static final int MAX_DEPTH = 8;
    /** The source files one index holds at most: a huge tree is indexed this far. */
    private static final int MAX_FILES = 200_000;
    /** A miss rebuilds the index (a file added since), at most this often. */
    private static final long REBUILD_MS = 5_000;

    private static final Pattern GRADLE_INCLUDE = Pattern.compile("\\binclude\\s*\\(?([^)\\n]*)");
    private static final Pattern QUOTED = Pattern.compile("[\"']([^\"']+)[\"']");
    private static final Pattern MAVEN_MODULE = Pattern.compile("<module>\\s*([^<]+?)\\s*</module>");
    private static final Pattern PACKAGE = Pattern.compile("^\\s*package\\s+([\\w.]+)", Pattern.MULTILINE);

    private static final Map<Path, Index> INDEXES = new ConcurrentHashMap<>();

    private DumpSources() {}

    /** The file names in a build's modules, and where each is. */
    private record Index(long builtAt, Map<String, List<Path>> byName) {}

    /**
     * The source of the class `className` written in `fileName` (null: its name's .java), for an app run from `root`;
     * `type` (null when unknown) the class itself, whose class directory names its module.
     */
    static Optional<Path> find(Path root, String className, String fileName, Class<?> type) {
        return find(root, className, fileName, location(type), classpath());
    }

    static Optional<Path> find(Path root, String className, String fileName, Path classes, List<Path> classpath) {
        int dot = className.lastIndexOf('.');
        String pkg = dot < 0 ? "" : className.substring(0, dot);
        String file = fileName != null ? fileName : className.substring(dot + 1).replaceAll("\\$.*", "") + ".java";
        String packagePath = pkg.isEmpty() ? file : pkg.replace('.', '/') + "/" + file;

        Path own = module(classes);
        Path buildRoot = buildRoot(root);
        Set<Path> scopes = new LinkedHashSet<>();
        if (own != null) scopes.add(own);
        scopes.add(buildRoot);
        for (Path entry : classpath) {
            Path module = module(entry);
            if (module != null) scopes.add(module);
        }
        for (Path scope : scopes) {
            Optional<Path> found = lookup(scope, file, packagePath, pkg, false);
            if (found.isPresent()) return found;
        }
        // Not in what was indexed: a file added since, once the index is old enough to be built again.
        for (Path scope : scopes) {
            Optional<Path> found = lookup(scope, file, packagePath, pkg, true);
            if (found.isPresent()) return found;
        }
        return Optional.empty();
    }

    /**
     * The build's root a directory is in: the nearest with a Gradle settings file, else the topmost pom.xml that is
     * the one below's parent directory or names it among its modules; itself when no build file is above it.
     */
    static Path buildRoot(Path start) {
        Path dir = start.toAbsolutePath().normalize();
        Path maven = null;
        Path home = Path.of(System.getProperty("user.home", "/")).toAbsolutePath().normalize();
        Path at = dir;
        // Never the home directory or above it: a stray build file there isn't the project's.
        for (int up = 0; at != null && up < MAX_UP && !home.startsWith(at); up++, at = at.getParent()) {
            if (Files.isRegularFile(at.resolve("settings.gradle.kts")) || Files.isRegularFile(at.resolve("settings.gradle"))) {
                return at;
            }
            if (Files.isRegularFile(at.resolve("pom.xml"))
                    && (maven == null || at.equals(maven.getParent()) || declaredModules(at, 0).contains(maven))) {
                maven = at;
            }
        }
        return maven != null ? maven : dir;
    }

    /** The module a class directory belongs to: the directory above its build/classes, target/classes, out/production. */
    static Path module(Path classes) {
        if (classes == null || !Files.isDirectory(classes)) return null;
        Path path = classes.toAbsolutePath().normalize();
        for (Path at = path; at != null && at.getParent() != null; at = at.getParent()) {
            String name = at.getFileName().toString();
            Path parent = at.getParent();
            if ((name.equals("build") || name.equals("target")) && !path.equals(at)) return parent;
            // IntelliJ's own build: out/production/<module>, the module named, not where it is: the project.
            if (name.equals("out") && (Files.isDirectory(at.resolve("production")) || Files.isDirectory(at.resolve("test")))) {
                return parent;
            }
        }
        return null;
    }

    private static Optional<Path> lookup(Path scope, String file, String packagePath, String pkg, boolean rebuild) {
        Index index = INDEXES.get(scope);
        long now = System.currentTimeMillis();
        if (rebuild && (index == null || now - index.builtAt() <= REBUILD_MS)) return Optional.empty();
        if (index == null || rebuild) {
            index = new Index(now, index(scope));
            INDEXES.put(scope, index);
        }
        List<Path> candidates = index.byName().getOrDefault(file, List.of());
        for (Path candidate : candidates) {
            if (candidate.toString().replace('\\', '/').endsWith("/" + packagePath)) return Optional.of(candidate);
        }
        for (Path candidate : candidates) {
            if (pkg.equals(declaredPackage(candidate))) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Every source file of the build's modules under `scope`, by its file name. */
    private static Map<String, List<Path>> index(Path scope) {
        Map<String, List<Path>> byName = new ConcurrentHashMap<>();
        Set<Path> modules = new LinkedHashSet<>();
        modules.add(scope);
        modules.addAll(declaredModules(scope, 0));
        modules(scope, 0, modules);
        int[] count = {0};
        for (Path module : modules) {
            Path src = module.resolve("src");
            if (!Files.isDirectory(src)) continue;
            for (Path root : sourceRoots(src)) {
                try (Stream<Path> files = Files.walk(root, 64)) {
                    files.filter(path -> count[0] < MAX_FILES && Files.isRegularFile(path) && source(path)).forEach(path -> {
                        count[0]++;
                        byName.computeIfAbsent(path.getFileName().toString(), name -> new ArrayList<>()).add(path);
                    });
                } catch (IOException | RuntimeException e) {
                    // That source root no further.
                }
            }
        }
        return byName;
    }

    /** A module's source roots: src/<set>/<language>, and src/<language> or src itself when its files sit there. */
    private static List<Path> sourceRoots(Path src) {
        List<Path> roots = new ArrayList<>();
        try (DirectoryStream<Path> sets = Files.newDirectoryStream(src, Files::isDirectory)) {
            for (Path set : sets) {
                if (LANGUAGES.contains(set.getFileName().toString())) {
                    roots.add(set);
                    continue;
                }
                boolean any = false;
                for (String language : LANGUAGES) {
                    Path dir = set.resolve(language);
                    if (Files.isDirectory(dir)) {
                        roots.add(dir);
                        any = true;
                    }
                }
                // A set with its files right in it (src/main/com/shop/App.kt, Amper's src/): itself.
                if (!any && !set.getFileName().toString().equals("resources")) roots.add(set);
            }
        } catch (IOException | RuntimeException e) {
            // None to read.
        }
        // Files right in src (src/App.kt).
        try (DirectoryStream<Path> files = Files.newDirectoryStream(src, path -> Files.isRegularFile(path) && source(path))) {
            if (files.iterator().hasNext()) roots.add(src);
        } catch (IOException | RuntimeException e) {
            // None.
        }
        return roots;
    }

    private static boolean source(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt") || name.endsWith(".kts") || name.endsWith(".groovy")
                || name.endsWith(".scala");
    }

    /** The modules a build file names: Gradle's includes (":libs:core" is libs/core), Maven's modules, at any depth. */
    private static Set<Path> declaredModules(Path dir, int depth) {
        Set<Path> found = new LinkedHashSet<>();
        if (depth > MAX_DEPTH) return found;
        for (String settings : List.of("settings.gradle.kts", "settings.gradle")) {
            String text = read(dir.resolve(settings));
            if (text == null) continue;
            Matcher include = GRADLE_INCLUDE.matcher(text);
            while (include.find()) {
                Matcher name = QUOTED.matcher(include.group(1));
                while (name.find()) {
                    String project = name.group(1).replaceAll("^:+", "").replace(':', '/');
                    if (!project.isEmpty()) found.add(dir.resolve(project).normalize());
                }
            }
        }
        String pom = read(dir.resolve("pom.xml"));
        if (pom != null) {
            Matcher module = MAVEN_MODULE.matcher(pom.replaceAll("(?s)<!--.*?-->", ""));
            while (module.find()) {
                Path child = dir.resolve(module.group(1)).normalize();
                if (Files.isRegularFile(child)) child = child.getParent();
                if (child != null && found.add(child)) found.addAll(declaredModules(child, depth + 1));
            }
        }
        return found;
    }

    /** The directories below `dir` with a src of their own: modules no build file named (a build of another kind). */
    private static void modules(Path dir, int depth, Set<Path> found) {
        if (depth >= MAX_DEPTH) return;
        try (DirectoryStream<Path> children = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path child : children) {
                String name = child.getFileName().toString();
                if (SKIPPED.contains(name) || name.startsWith(".") || name.equals("src") || Files.isSymbolicLink(child)) continue;
                if (Files.isDirectory(child.resolve("src"))) found.add(child);
                modules(child, depth + 1, found);
            }
        } catch (IOException | RuntimeException e) {
            // Not below this one.
        }
    }

    private static String declaredPackage(Path file) {
        String text = read(file);
        if (text == null) return null;
        Matcher matcher = PACKAGE.matcher(text.length() > 8192 ? text.substring(0, 8192) : text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String read(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Where the class was loaded from, when that is a directory (a dev build's classes, not a jar). */
    private static Path location(Class<?> type) {
        if (type == null) return null;
        try {
            CodeSource code = type.getProtectionDomain().getCodeSource();
            URL url = code == null ? null : code.getLocation();
            if (url == null || !"file".equals(url.getProtocol())) return null;
            Path path = Path.of(url.toURI());
            return Files.isDirectory(path) ? path : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** The classpath's directories. */
    private static List<Path> classpath() {
        List<Path> dirs = new ArrayList<>();
        String classpath = System.getProperty("java.class.path", "");
        for (String entry : classpath.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            try {
                Path path = Path.of(entry);
                if (Files.isDirectory(path)) dirs.add(path);
            } catch (RuntimeException e) {
                // Not a path.
            }
        }
        return dirs;
    }

    /** For tests: indexes built again on their next use. */
    static void forget() {
        INDEXES.clear();
    }
}
