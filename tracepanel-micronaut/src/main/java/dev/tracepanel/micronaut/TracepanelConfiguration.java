package dev.tracepanel.micronaut;

import dev.tracepanel.core.Config;
import dev.tracepanel.core.Tracepanel;
import dev.tracepanel.integrations.Log4j2Recorder;
import dev.tracepanel.integrations.LogbackRecorder;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.core.reflect.ClassUtils;
import io.micronaut.core.version.VersionUtils;
import jakarta.inject.Singleton;
import java.util.List;

/**
 * Tracepanel in a Micronaut app, from the dependency alone: each request, SQL through its DataSources, calls through
 * its HTTP clients, logs, the widget and the console. Meant for development: add it as developmentOnly (Gradle) or an
 * optional dependency the app's production build leaves out; TRACEPANEL_ENABLED=0 turns it off.
 */
@Factory
@Requires(condition = TracepanelEnabled.class)
final class TracepanelConfiguration {
    @Singleton
    Tracepanel tracepanel(Environment environment) {
        // Each setting from its variable (TRACEPANEL_SLOW_SQL_MS), else from the app's properties (tracepanel.slow-sql-ms).
        Config config = Config.load(
                name -> environment.getProperty(name, String.class)
                        .or(() -> environment.getProperty(Config.property(name), String.class)).orElse(null),
                environment.getProperty("micronaut.application.name", String.class).orElse(null));
        // The app's packages, as Micronaut.run(Application.class) names them: its frames in stacks.
        Tracepanel tracepanel = Tracepanel.start(config, List.copyOf(environment.getPackages()));
        tracepanel.framework("Micronaut " + VersionUtils.MICRONAUT_VERSION);
        if (isPresent("ch.qos.logback.classic.LoggerContext")) LogbackRecorder.install(tracepanel);
        if (isPresent("org.apache.logging.log4j.core.LoggerContext")) Log4j2Recorder.install(tracepanel);
        return tracepanel;
    }

    private static boolean isPresent(String name) {
        return ClassUtils.isPresent(name, TracepanelConfiguration.class.getClassLoader());
    }
}
