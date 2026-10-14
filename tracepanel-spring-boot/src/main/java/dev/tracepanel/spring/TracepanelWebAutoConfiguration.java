package dev.tracepanel.spring;

import dev.tracepanel.core.Tracepanel;
import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** A Spring MVC app's requests, their handlers and errors, the widget on its pages, and the console under /__tracepanel__/. */
@AutoConfiguration(after = TracepanelAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnBean(Tracepanel.class)
public class TracepanelWebAutoConfiguration {

    @Bean
    FilterRegistrationBean<TracepanelFilter> tracepanelFilter(Tracepanel tracepanel, BeanFactory beans) {
        String framework = "Spring Boot " + SpringBootVersion.getVersion();
        String kotlin = TracepanelAutoConfiguration.kotlinVersion(beans);
        String language = kotlin != null ? "Kotlin " + TracepanelAutoConfiguration.minor(kotlin) : "Java " + Runtime.version().feature();
        String runtime = language + " · Spring Boot " + TracepanelAutoConfiguration.minor(SpringBootVersion.getVersion());
        FilterRegistrationBean<TracepanelFilter> registration =
                new FilterRegistrationBean<>(new TracepanelFilter(tracepanel, framework, runtime));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
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

}
