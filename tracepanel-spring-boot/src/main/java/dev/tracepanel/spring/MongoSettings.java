package dev.tracepanel.spring;

import com.mongodb.MongoClientSettings;
import dev.tracepanel.integrations.MongoCommands;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** The settings Spring Boot builds its MongoClient from, with the command listener: each client the app gets. */
final class MongoSettings implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        return bean instanceof MongoClientSettings settings ? MongoCommands.settings(settings) : bean;
    }
}
