package dev.tracepanel.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where Tracepanel keeps its state and what it calls the app, from the same TRACEPANEL_* variables as the other SDKs.
 *
 * @param enabled TRACEPANEL_ENABLED; on unless it says otherwise (the dependency is for development only)
 * @param root the project's directory: the store lives in it, and stacks name files below it
 * @param storeDir the agent's store (.tracepanel), where it leaves its addresses
 * @param service the app's name on the services map
 * @param maxTraces how many traces the agent keeps
 * @param thresholds the limits the agent finds the issues with, as the other SDKs name them
 * @param prefix where the console answers on the app's own origin
 * @param consoleRemote TRACEPANEL_CONSOLE_REMOTE: whether other machines may read the console; this one only by default
 * @param captureRequest TRACEPANEL_CAPTURE_REQUEST: a request's headers, query, body (what the app read), cookies and
 *     session in its trace, masked; on by default (they never leave this machine)
 * @param profile TRACEPANEL_PROFILE: every trace profiled; off by default, when a request still may ask (an
 *     X-Tracepanel-Profile: 1 header, or the tracepanel_profile=1 cookie the panel's Profile button sets)
 * @param profileEnabled TRACEPANEL_PROFILE_ENABLED: whether profiling is possible at all; off, nothing profiles
 * @param profileIntervalMs TRACEPANEL_PROFILE_INTERVAL_MS: how often a profiled trace's stack is sampled
 */
public record Config(
        boolean enabled,
        Path root,
        Path storeDir,
        String service,
        int maxTraces,
        Map<String, Number> thresholds,
        String prefix,
        boolean consoleRemote,
        boolean captureRequest,
        boolean profile,
        boolean profileEnabled,
        int profileIntervalMs) {

    private static final Set<String> OFF = Set.of("0", "false", "no", "off");

    /** Each threshold: its variable and its default, the agent's own. */
    private static final Map<String, Object[]> THRESHOLDS = new LinkedHashMap<>();

    static {
        THRESHOLDS.put("n_plus_one", new Object[] {"TRACEPANEL_N_PLUS_ONE_THRESHOLD", 3});
        THRESHOLDS.put("n_plus_one_min_ms", new Object[] {"TRACEPANEL_N_PLUS_ONE_MIN_MS", 5});
        THRESHOLDS.put("slow_sql_ms", new Object[] {"TRACEPANEL_SLOW_SQL_MS", 100});
        THRESHOLDS.put("slow_http_ms", new Object[] {"TRACEPANEL_SLOW_HTTP_MS", 500});
        THRESHOLDS.put("long_transaction_ms", new Object[] {"TRACEPANEL_LONG_TRANSACTION_MS", 500});
        THRESHOLDS.put("queue_wait_ms", new Object[] {"TRACEPANEL_QUEUE_WAIT_MS", 1000});
        THRESHOLDS.put("large_result_rows", new Object[] {"TRACEPANEL_LARGE_RESULT_ROWS", 1000});
    }

    /** From the environment; `fallbackService` names the app when TRACEPANEL_SERVICE doesn't (spring.application.name). */
    public static Config load(Map<String, String> env, String fallbackService) {
        Path root = Path.of(env.getOrDefault("TRACEPANEL_ROOT", System.getProperty("user.dir"))).toAbsolutePath();
        String service = nonEmpty(env.get("TRACEPANEL_SERVICE"));
        if (service == null) service = nonEmpty(fallbackService);
        if (service == null) service = root.getFileName() == null ? "app" : root.getFileName().toString();
        return new Config(
                enabled(env.get("TRACEPANEL_ENABLED")),
                root,
                root.resolve(env.getOrDefault("TRACEPANEL_DIR", ".tracepanel")),
                service,
                integer(env.get("TRACEPANEL_MAX_TRACES"), 2000),
                thresholds(env),
                "/__tracepanel__/",
                on(env.get("TRACEPANEL_CONSOLE_REMOTE")),
                enabled(env.get("TRACEPANEL_CAPTURE_REQUEST")),
                on(env.get("TRACEPANEL_PROFILE")),
                enabled(env.get("TRACEPANEL_PROFILE_ENABLED")),
                // Each millisecond, as in the Python SDK: a request of a few milliseconds still has a profile to read.
                Math.max(integer(env.get("TRACEPANEL_PROFILE_INTERVAL_MS"), 1), 1));
    }

    public static Config load(String fallbackService) {
        return load(System.getenv(), fallbackService);
    }

    /** A setting that is off unless it says on (1, true, yes, on). */
    private static boolean on(String value) {
        String explicit = nonEmpty(value);
        return explicit != null && !OFF.contains(explicit.toLowerCase(Locale.ROOT));
    }

    private static boolean enabled(String value) {
        String explicit = nonEmpty(value);
        return explicit == null || !OFF.contains(explicit.toLowerCase(Locale.ROOT));
    }

    private static Map<String, Number> thresholds(Map<String, String> env) {
        Map<String, Number> found = new LinkedHashMap<>();
        THRESHOLDS.forEach((name, spec) -> found.put(name, number(env.get((String) spec[0]), (Integer) spec[1])));
        return found;
    }

    private static Number number(String value, int fallback) {
        try {
            double parsed = Double.parseDouble(value);
            return parsed == Math.rint(parsed) ? (Number) (long) parsed : (Number) parsed;
        } catch (NullPointerException | NumberFormatException e) {
            return fallback;
        }
    }

    private static int integer(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String nonEmpty(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
