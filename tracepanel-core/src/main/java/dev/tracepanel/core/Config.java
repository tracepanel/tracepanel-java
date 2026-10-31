package dev.tracepanel.core;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Where Tracepanel keeps its state and what it calls the app, from the same TRACEPANEL_* variables as the other SDKs.
 *
 * @param enabled TRACEPANEL_ENABLED; on unless it says otherwise (the dependency is for development only)
 * @param root the project's directory: the store lives in it, and stacks name files below it
 * @param storeDir the agent's store (.tracepanel), where it leaves its addresses
 * @param service the app's name on the services map
 * @param maxTraces how many traces the agent keeps
 * @param thresholds the limits the agent finds the issues with, as the other SDKs name them; slow_sql_ms_by_db and
 *     long_transaction_ms_by_db, when set, hold one database (by its db.system) to its own limit
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
        Map<String, Object> thresholds,
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

    /** The thresholds that take a limit per database too: `100,snowflake=3000`. */
    private static final Set<String> BY_DATABASE = Set.of("slow_sql_ms", "long_transaction_ms");

    /** From the environment; `fallbackService` names the app when TRACEPANEL_SERVICE doesn't (spring.application.name). */
    public static Config load(Map<String, String> env, String fallbackService) {
        return load((Function<String, String>) env::get, fallbackService);
    }

    /**
     * From `settings`, asked by each variable's name (TRACEPANEL_SLOW_SQL_MS): a framework's lookup answers from its
     * own configuration too (tracepanel.slow-sql-ms, see {@link #property}).
     */
    public static Config load(Function<String, String> settings, String fallbackService) {
        Function<String, String> env = name -> nonEmpty(settings.apply(name));
        Path root = Path.of(Objects.requireNonNullElse(env.apply("TRACEPANEL_ROOT"), System.getProperty("user.dir"))).toAbsolutePath();
        String service = nonEmpty(env.apply("TRACEPANEL_SERVICE"));
        if (service == null) service = nonEmpty(fallbackService);
        if (service == null) service = root.getFileName() == null ? "app" : root.getFileName().toString();
        return new Config(
                enabled(env.apply("TRACEPANEL_ENABLED")),
                root,
                root.resolve(Objects.requireNonNullElse(env.apply("TRACEPANEL_DIR"), ".tracepanel")),
                service,
                integer(env.apply("TRACEPANEL_MAX_TRACES"), 2000),
                thresholds(env),
                "/__tracepanel__/",
                on(env.apply("TRACEPANEL_CONSOLE_REMOTE")),
                enabled(env.apply("TRACEPANEL_CAPTURE_REQUEST")),
                on(env.apply("TRACEPANEL_PROFILE")),
                enabled(env.apply("TRACEPANEL_PROFILE_ENABLED")),
                // Each millisecond, as in the Python SDK: a request of a few milliseconds still has a profile to read.
                Math.max(integer(env.apply("TRACEPANEL_PROFILE_INTERVAL_MS"), 1), 1));
    }

    public static Config load(String fallbackService) {
        return load(System.getenv(), fallbackService);
    }

    /** A variable's name as a configuration property: TRACEPANEL_SLOW_SQL_MS is tracepanel.slow-sql-ms. */
    public static String property(String variable) {
        return "tracepanel." + variable.substring("TRACEPANEL_".length()).toLowerCase(Locale.ROOT).replace('_', '-');
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

    /**
     * The thresholds the settings name, the agent's defaults otherwise. TRACEPANEL_SLOW_SQL_MS and
     * TRACEPANEL_LONG_TRANSACTION_MS take a limit per database too, by its db.system: `100,snowflake=3000` holds every
     * database to 100 ms but Snowflake, to 3 s (slow_sql_ms_by_db). An entry that isn't a number is left out.
     */
    private static Map<String, Object> thresholds(Function<String, String> env) {
        Map<String, Object> found = new LinkedHashMap<>();
        Map<String, Map<String, Number>> byDatabase = new LinkedHashMap<>();
        THRESHOLDS.forEach((name, spec) -> {
            Number all = (Integer) spec[1];
            String value = env.apply((String) spec[0]);
            for (String entry : value == null ? new String[0] : value.split(",")) {
                int equals = entry.indexOf('=');
                Number number = number(equals < 0 ? entry.trim() : entry.substring(equals + 1).trim(), null);
                if (number == null || number.doubleValue() < 0) continue;
                if (equals < 0) {
                    all = number;
                    continue;
                }
                String system = entry.substring(0, equals).trim().toLowerCase(Locale.ROOT);
                if (BY_DATABASE.contains(name) && !system.isEmpty()) {
                    byDatabase.computeIfAbsent(name + "_by_db", key -> new LinkedHashMap<>()).put(system, number);
                }
            }
            found.put(name, all);
        });
        found.putAll(byDatabase);
        return found;
    }

    private static Number number(String value, Number fallback) {
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
