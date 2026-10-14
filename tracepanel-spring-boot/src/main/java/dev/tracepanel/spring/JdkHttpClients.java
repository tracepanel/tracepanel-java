package dev.tracepanel.spring;

import dev.tracepanel.core.HttpCalls;
import java.net.http.HttpClient;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** The app's JDK HttpClient beans, each call through them recorded. One the app makes itself is HttpCalls.wrap(it). */
final class JdkHttpClients implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof HttpClient client ? HttpCalls.wrap(client) : bean;
    }
}
