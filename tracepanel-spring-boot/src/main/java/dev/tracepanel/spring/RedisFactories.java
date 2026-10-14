package dev.tracepanel.spring;

import dev.tracepanel.integrations.RedisCommands;
import io.lettuce.core.AbstractRedisClient;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** Each Lettuce connection factory's client gets the command listener, with the server it talks to. */
final class RedisFactories implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof LettuceConnectionFactory factory)) return bean;
        AbstractRedisClient client = factory.getNativeClient();
        if (client != null) client.addListener(new RedisCommands(factory.getHostName(), factory.getPort(), factory.getDatabase()));
        return bean;
    }
}
