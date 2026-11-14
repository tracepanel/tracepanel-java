package dev.tracepanel.micronaut;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TracepanelEnabledTest {
    @Test
    void developmentEnvironmentsEnableTracingButProductionDoesNot() {
        for (String name : new String[] {"dev", "development", "local", "production", "test"}) {
            assertEquals(!name.equals("production") && !name.equals("test"), enabled(name, Map.of()));
        }
    }

    @Test
    void explicitSettingsWinOverEnvironmentNames() {
        for (String off : new String[] {"0", "false", "no", "OFF"}) {
            assertEquals(false, enabled("dev", Map.of("TRACEPANEL_ENABLED", off)));
        }
        assertEquals(true, enabled("production", Map.of("tracepanel.enabled", "true")));
        assertEquals(false, enabled("dev", Map.of("TRACEPANEL_ENABLED", "0", "tracepanel.enabled", "1")));
    }

    private static boolean enabled(String name, Map<String, Object> properties) {
        try (ApplicationContext context = ApplicationContext.builder().deduceEnvironment(false)
                .environments(name).properties(properties).build()) {
            Environment environment = context.getEnvironment().start();
            return TracepanelEnabled.enabled(environment);
        }
    }
}
