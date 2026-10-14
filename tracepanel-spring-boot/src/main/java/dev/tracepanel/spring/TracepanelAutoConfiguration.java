package dev.tracepanel.spring;

import dev.tracepanel.core.Config;
import dev.tracepanel.core.Tracepanel;
import java.lang.reflect.Method;
import java.util.List;
import java.lang.annotation.Annotation;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.StaticMethodMatcherPointcutAdvisor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Role;
import org.springframework.core.task.TaskDecorator;
import org.springframework.core.env.Environment;

/**
 * Tracepanel in a Spring Boot app, from the dependency alone: SQL, calls to HTTP APIs, logs, and the work it does
 * besides requests. The web part (TracepanelWebAutoConfiguration) adds each request, the widget and the console.
 * Meant for development: add it as developmentOnly (Gradle) or an optional dependency the app's production build
 * leaves out; TRACEPANEL_ENABLED=0 turns it off.
 */
@AutoConfiguration
@Conditional(TracepanelEnabled.class)
public class TracepanelAutoConfiguration {

    @Bean
    Tracepanel tracepanel(Environment environment, BeanFactory beans) {
        Config config = Config.load(environment.getProperty("spring.application.name"));
        List<String> packages = AutoConfigurationPackages.has(beans) ? AutoConfigurationPackages.get(beans) : List.of();
        Tracepanel tracepanel = Tracepanel.start(config, packages);
        String kotlin = kotlinVersion(beans);
        if (kotlin != null) tracepanel.language("kotlin", kotlin);
        tracepanel.framework("Spring Boot " + SpringBootVersion.getVersion());
        if (isPresent("ch.qos.logback.classic.LoggerContext")) LogbackRecorder.install(tracepanel);
        return tracepanel;
    }

    @Bean
    static DataSources tracepanelDataSources() {
        return new DataSources();
    }

    /** Calls to HTTP APIs through Spring's clients: RestClient, RestTemplate (their builders' too). */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.client.RestClient")
    static RestClients tracepanelRestClients() {
        return new RestClients();
    }

    /** @Async, @Scheduled and runners' work, each a trace: Spring's infrastructure proxies apply it. */
    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    static Advisor tracepanelBackground() {
        return new StaticMethodMatcherPointcutAdvisor(new Background()) {
            @Override
            public boolean matches(Method method, Class<?> type) {
                return Background.traced(method, type);
            }
        };
    }

    /** The executor @Async methods run on links each task to the trace that queued it; an app's own decorator wins. */
    @Bean
    @ConditionalOnMissingBean(TaskDecorator.class)
    TaskDecorator tracepanelQueuing() {
        return new Background.Queuing();
    }

    /** MongoDB commands, from the settings Spring Boot builds its MongoClient from. */
    @Bean
    @ConditionalOnClass(name = "com.mongodb.MongoClientSettings")
    static MongoCommands.Settings tracepanelMongo() {
        return new MongoCommands.Settings();
    }

    /** Redis commands through Lettuce, from each of Spring Data Redis's connection factories. */
    @Bean
    @ConditionalOnClass(name = {"io.lettuce.core.event.command.CommandListener",
            "org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory"})
    static RedisCommands.Factories tracepanelRedis() {
        return new RedisCommands.Factories();
    }

    /** And the JDK's HttpClient beans. */
    @Bean
    static JdkHttpClients tracepanelJdkHttpClients() {
        return new JdkHttpClients();
    }

    /** And through WebClient, in a WebFlux app or beside Spring MVC. */
    @Bean
    @ConditionalOnClass(name = "org.springframework.web.reactive.function.client.WebClient")
    static WebClients tracepanelWebClients() {
        return new WebClients();
    }

    /**
     * Kotlin's version when the app is a Kotlin one (its @SpringBootApplication class compiled by Kotlin, which
     * marks each class with kotlin.Metadata), else null.
     */
    static String kotlinVersion(BeanFactory beans) {
        if (!(beans instanceof ListableBeanFactory listable)) return null;
        for (String name : listable.getBeanNamesForAnnotation(SpringBootApplication.class)) {
            Class<?> type = listable.getType(name);
            if (type == null) continue;
            for (Annotation annotation : type.getAnnotations()) {
                if (annotation.annotationType().getName().equals("kotlin.Metadata")) return kotlinRuntime();
            }
        }
        return null;
    }

    private static String kotlinRuntime() {
        try {
            return String.valueOf(Class.forName("kotlin.KotlinVersion").getField("CURRENT").get(null));
        } catch (ReflectiveOperationException | LinkageError e) {
            return "";
        }
    }

    static String minor(String version) {
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
