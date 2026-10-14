package dev.tracepanel.spring;

import dev.tracepanel.core.Config;
import dev.tracepanel.core.Tracepanel;
import jakarta.servlet.DispatcherType;
import java.util.List;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Tracepanel in a Spring MVC app, from the dependency alone: requests, their handlers, SQL, logs, errors, the
 * widget, and the console under /__tracepanel__/. Meant for development: add it as developmentOnly (Gradle) or an
 * optional dependency the app's production build leaves out; TRACEPANEL_ENABLED=0 turns it off.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@Conditional(TracepanelEnabled.class)
public class TracepanelAutoConfiguration {

    @Bean
    Tracepanel tracepanel(Environment environment, BeanFactory beans) {
        Config config = Config.load(environment.getProperty("spring.application.name"));
        List<String> packages = AutoConfigurationPackages.has(beans) ? AutoConfigurationPackages.get(beans) : List.of();
        Tracepanel tracepanel = Tracepanel.start(config, packages);
        if (isPresent("ch.qos.logback.classic.LoggerContext")) LogbackRecorder.install(tracepanel);
        return tracepanel;
    }

    @Bean
    FilterRegistrationBean<TracepanelFilter> tracepanelFilter(Tracepanel tracepanel) {
        String framework = "Spring Boot " + SpringBootVersion.getVersion();
        String runtime = "Java " + Runtime.version().feature() + " · Spring Boot " + minor(SpringBootVersion.getVersion());
        FilterRegistrationBean<TracepanelFilter> registration =
                new FilterRegistrationBean<>(new TracepanelFilter(tracepanel, framework, runtime));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    WebMvcConfigurer tracepanelViews() {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new ViewInterceptor()).order(Ordered.HIGHEST_PRECEDENCE);
            }
        };
    }

    @Bean
    ErrorRecorder tracepanelErrors(Tracepanel tracepanel) {
        return new ErrorRecorder(tracepanel);
    }

    @Bean
    static DataSources tracepanelDataSources() {
        return new DataSources();
    }

    private static String minor(String version) {
        String[] parts = version == null ? new String[0] : version.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : String.valueOf(version);
    }

    private static boolean isPresent(String className) {
        try {
            Class.forName(className, false, TracepanelAutoConfiguration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
