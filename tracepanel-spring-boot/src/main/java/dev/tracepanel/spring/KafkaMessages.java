package dev.tracepanel.spring;

import dev.tracepanel.core.Messages;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.W3c;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/**
 * Kafka through Spring for Apache Kafka: each record a KafkaTemplate sends is a kafka span (publish orders) of the
 * trace that sent it, its headers carrying a traceparent; each record a listener receives is a task trace, linked to
 * the publish by it and named after the @KafkaListener method that handles it.
 */
final class KafkaMessages {
    private KafkaMessages() {}

    /** KafkaTemplate's sends, until the producer took the record; the producer factories add the headers. */
    static final class Templates implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof DefaultKafkaProducerFactory<?, ?> factory) {
                addInterceptor(factory);
                return bean;
            }
            if (!(bean instanceof KafkaTemplate<?, ?> template)) return bean;
            ProxyFactory proxy = new ProxyFactory(bean);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                String method = invocation.getMethod().getName();
                if (!method.equals("send") && !method.equals("sendDefault")) return invocation.proceed();
                Messages.Publish publish = Messages.publish("kafka");
                if (publish == null) return invocation.proceed();
                publish.destination(method.equals("sendDefault") ? template.getDefaultTopic() : topic(invocation.getArguments()));
                // Recorded as the producer takes it: the broker's acknowledgement comes after the request may have
                // ended, and its trace with it.
                try {
                    Object result = invocation.proceed();
                    publish.sent();
                    return result;
                } catch (Throwable error) {
                    publish.failed(error);
                    throw error;
                }
            });
            return proxy.getProxy(bean.getClass().getClassLoader());
        }

        private static String topic(Object[] args) {
            if (args.length == 0) return null;
            if (args[0] instanceof String topic) return topic;
            if (args[0] instanceof ProducerRecord<?, ?> record) return record.topic();
            if (args[0] instanceof Message<?> message) return String.valueOf(message.getHeaders().get(KafkaHeaders.TOPIC));
            return null;
        }

        /** The producer's interceptors, with this one after the app's own. */
        private static void addInterceptor(DefaultKafkaProducerFactory<?, ?> factory) {
            Object configured = factory.getConfigurationProperties().get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
            List<String> classes = new ArrayList<>();
            if (configured instanceof String names && !names.isBlank()) classes.addAll(Arrays.asList(names.split("\\s*,\\s*")));
            else if (configured instanceof List<?> list) list.forEach(item -> classes.add(item instanceof Class<?> type ? type.getName() : String.valueOf(item)));
            else if (configured instanceof Class<?> type) classes.add(type.getName());
            if (classes.contains(Headers.class.getName())) return;
            classes.add(Headers.class.getName());
            factory.updateConfigs(Map.of(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, String.join(",", classes)));
        }
    }

    /** In the producer, on the sending thread: the record of the publish under way gets its traceparent. */
    public static final class Headers implements ProducerInterceptor<Object, Object> {
        @Override
        public ProducerRecord<Object, Object> onSend(ProducerRecord<Object, Object> record) {
            Messages.Publish publish = Messages.publishing();
            if (publish != null && record.headers().lastHeader(W3c.HEADER) == null) {
                publish.destination(record.topic());
                record.headers().add(W3c.HEADER, publish.traceparent().getBytes(StandardCharsets.UTF_8));
                record.headers().add(Messages.SENT_BY, publish.sentBy().getBytes(StandardCharsets.UTF_8));
            }
            return record;
        }

        @Override
        public void onAcknowledgement(RecordMetadata metadata, Exception exception) {}

        @Override
        public void close() {}

        @Override
        public void configure(Map<String, ?> configs) {}
    }

    /** Each record a listener container hands to its listener: a task trace, from before the listener to after it. */
    static final class Records implements RecordInterceptor<Object, Object> {
        private final ThreadLocal<Trace> receiving = new ThreadLocal<>();
        private final ThreadLocal<Exception> failed = new ThreadLocal<>();

        @Override
        public ConsumerRecord<Object, Object> intercept(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            Header header = record.headers().lastHeader(W3c.HEADER);
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("messaging.kafka.partition", record.partition());
            attrs.put("messaging.kafka.offset", record.offset());
            Header sentBy = record.headers().lastHeader(Messages.SENT_BY);
            Trace trace = Messages.receive("kafka", record.topic(),
                    header == null ? null : new String(header.value(), StandardCharsets.UTF_8),
                    sentBy == null ? null : new String(sentBy.value(), StandardCharsets.UTF_8), attrs);
            if (trace != null) receiving.set(trace);
            return record;
        }

        @Override
        public void failure(ConsumerRecord<Object, Object> record, Exception exception, Consumer<Object, Object> consumer) {
            failed.set(exception);
        }

        @Override
        public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
            Trace trace = receiving.get();
            Exception error = failed.get();
            receiving.remove();
            failed.remove();
            Messages.done(trace, error);
        }
    }
}
