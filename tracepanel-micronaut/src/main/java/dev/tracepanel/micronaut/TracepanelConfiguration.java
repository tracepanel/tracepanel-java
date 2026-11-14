package dev.tracepanel.micronaut;

import dev.tracepanel.core.Config;
import dev.tracepanel.core.Tracepanel;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.version.VersionUtils;
import jakarta.inject.Singleton;
import java.util.List;

@Factory
@Requires(condition = TracepanelEnabled.class)
final class TracepanelConfiguration {
    @Singleton
    Tracepanel tracepanel(Environment environment) {
        Config config = Config.load(name -> environment.getProperty(name, String.class)
                .or(() -> environment.getProperty(Config.property(name), String.class)).orElse(null),
                environment.getProperty("micronaut.application.name", String.class).orElse(null));
        Tracepanel tracepanel = Tracepanel.start(config, List.of());
        tracepanel.framework("Micronaut " + VersionUtils.MICRONAUT_VERSION);
        if (ClassUtils.isPresent("ch.qos.logback.classic.LoggerContext", getClass().getClassLoader())) {
            dev.tracepanel.integrations.LogbackRecorder.install(tracepanel);
        }
        return tracepanel;
    }
}
