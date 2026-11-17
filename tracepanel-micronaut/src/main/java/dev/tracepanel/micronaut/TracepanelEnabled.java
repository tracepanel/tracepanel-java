package dev.tracepanel.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.context.env.Environment;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * On in development only: when TRACEPANEL_ENABLED (or tracepanel.enabled) says so, else when an environment names
 * development (dev, development, local). A dependency that reached a production build by mistake records nothing and
 * serves no console there.
 */
final class TracepanelEnabled implements Condition {
    private static final Set<String> OFF = Set.of("0", "false", "no", "off");
    private static final Set<String> DEVELOPMENT = Set.of("dev", "development", "local");
    private static final Logger LOG = LoggerFactory.getLogger(TracepanelEnabled.class);
    private static volatile boolean told;

    @Override
    @SuppressWarnings("rawtypes")
    public boolean matches(ConditionContext context) {
        if (!(context.getBeanContext() instanceof ApplicationContext application)) return false;
        boolean on = enabled(application.getEnvironment());
        if (!on && !told) {
            told = true;
            LOG.info("Tracepanel is off: no dev or local environment, so this may not be development. "
                    + "TRACEPANEL_ENABLED=1 turns it on.");
        }
        return on;
    }

    static boolean enabled(Environment environment) {
        String value = environment.getProperty("TRACEPANEL_ENABLED", String.class)
                .or(() -> environment.getProperty("tracepanel.enabled", String.class)).orElse("").trim();
        if (!value.isEmpty()) return !OFF.contains(value.toLowerCase(Locale.ROOT));
        return environment.getActiveNames().stream()
                .anyMatch(name -> DEVELOPMENT.contains(name.toLowerCase(Locale.ROOT)));
    }
}
