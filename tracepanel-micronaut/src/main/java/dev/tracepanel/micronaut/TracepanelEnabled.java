package dev.tracepanel.micronaut;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.condition.Condition;
import io.micronaut.context.condition.ConditionContext;
import io.micronaut.context.env.Environment;
import java.util.Locale;
import java.util.Set;

final class TracepanelEnabled implements Condition {
    private static final Set<String> OFF = Set.of("0", "false", "no", "off");
    private static final Set<String> DEVELOPMENT = Set.of("dev", "development", "local");

    @Override
    @SuppressWarnings("rawtypes")
    public boolean matches(ConditionContext context) {
        if (!(context.getBeanContext() instanceof ApplicationContext application)) return false;
        return enabled(application.getEnvironment());
    }

    static boolean enabled(Environment environment) {
        String explicit = environment.getProperty("TRACEPANEL_ENABLED", String.class)
                .or(() -> environment.getProperty("tracepanel.enabled", String.class)).orElse("").trim();
        if (!explicit.isEmpty()) return !OFF.contains(explicit.toLowerCase(Locale.ROOT));
        return environment.getActiveNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT)).anyMatch(DEVELOPMENT::contains);
    }
}
