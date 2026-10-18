package dev.tracepanel.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** When Tracepanel turns on in a Spring Boot app: in development, or when it is told to. */
class TracepanelEnabledTest {
    @Configuration
    @Conditional(TracepanelEnabled.class)
    static class Probe {
        @Bean
        String probe() {
            return "on";
        }
    }

    private boolean on(String[] profiles, String... properties) {
        boolean[] found = {false};
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profiles))
                .withPropertyValues(properties)
                .withUserConfiguration(Probe.class)
                .run(context -> found[0] = context.containsBean("probe"));
        return found[0];
    }

    @Test
    void off_without_a_development_profile_or_devtools() {
        assertEquals(false, on(new String[0]));
        assertEquals(false, on(new String[] {"prod"}));
    }

    @Test
    void on_with_a_profile_that_names_development() {
        assertEquals(true, on(new String[] {"dev"}));
        assertEquals(true, on(new String[] {"Local"}));
        assertEquals(true, on(new String[] {"cloud", "development"}));
    }

    @Test
    void the_setting_decides_over_the_profiles() {
        assertEquals(true, on(new String[] {"prod"}, "TRACEPANEL_ENABLED=1"));
        assertEquals(true, on(new String[0], "tracepanel.enabled=true"));
        assertEquals(false, on(new String[] {"dev"}, "TRACEPANEL_ENABLED=0"));
    }
}
