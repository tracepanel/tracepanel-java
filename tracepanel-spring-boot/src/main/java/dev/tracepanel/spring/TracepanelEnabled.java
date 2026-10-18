package dev.tracepanel.spring;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.ClassUtils;

/**
 * On in development only: when TRACEPANEL_ENABLED (or tracepanel.enabled) says so, else when a profile names
 * development (dev, development, local) or Spring Boot DevTools is on the classpath. A dependency that reached a
 * production build by mistake records nothing and serves no console there.
 */
final class TracepanelEnabled implements Condition {
    private static final Set<String> OFF = Set.of("0", "false", "no", "off");
    private static final Set<String> DEVELOPMENT = Set.of("dev", "development", "local");
    private static final String DEVTOOLS = "org.springframework.boot.devtools.restart.Restarter";
    private static final Log LOG = LogFactory.getLog(TracepanelEnabled.class);
    private static volatile boolean told;

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        String value = environment.getProperty("TRACEPANEL_ENABLED", environment.getProperty("tracepanel.enabled", ""));
        if (!value.isBlank()) return !OFF.contains(value.toLowerCase(Locale.ROOT));
        boolean development = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> DEVELOPMENT.contains(profile.toLowerCase(Locale.ROOT)));
        if (development || ClassUtils.isPresent(DEVTOOLS, context.getClassLoader())) return true;
        if (!told) {
            told = true;
            LOG.info("Tracepanel is off: no dev or local profile and no DevTools, so this may not be development. "
                    + "TRACEPANEL_ENABLED=1 turns it on.");
        }
        return false;
    }
}
