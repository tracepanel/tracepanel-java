package dev.tracepanel.spring;

import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** On unless TRACEPANEL_ENABLED (or tracepanel.enabled) says otherwise: the dependency is development's alone. */
final class TracepanelEnabled implements Condition {
    private static final Set<String> OFF = Set.of("0", "false", "no", "off");

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String value = context.getEnvironment().getProperty("TRACEPANEL_ENABLED",
                context.getEnvironment().getProperty("tracepanel.enabled", ""));
        return value.isBlank() || !OFF.contains(value.toLowerCase(Locale.ROOT));
    }
}
