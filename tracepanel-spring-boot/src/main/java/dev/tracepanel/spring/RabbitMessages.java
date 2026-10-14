package dev.tracepanel.spring;

import dev.tracepanel.core.Messages;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.W3c;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Correlation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.config.BaseRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * RabbitMQ through Spring AMQP: each message a RabbitTemplate publishes is a rabbitmq span (publish orders) of the
 * trace that sent it, its headers carrying a traceparent; each message a listener container delivers is a task
 * trace, linked to the publish by it and named after the @RabbitListener method that handles it.
 */
final class RabbitMessages {
    private RabbitMessages() {}

    /** RabbitTemplate's publishes, and the listener containers' deliveries. */
    static final class Beans implements BeanPostProcessor {
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
            if (bean instanceof BaseRabbitListenerContainerFactory<?> factory) {
                Advice[] chain = factory.getAdviceChain();
                List<Advice> advice = new ArrayList<>(chain == null ? List.of() : Arrays.asList(chain));
                if (advice.stream().noneMatch(Deliveries.class::isInstance)) advice.add(0, new Deliveries());
                factory.setAdviceChain(advice.toArray(Advice[]::new));
                return bean;
            }
            if (!(bean instanceof RabbitTemplate template)) return bean;
            template.addBeforePublishPostProcessors(new Headers());
            ProxyFactory proxy = new ProxyFactory(bean);
            proxy.setProxyTargetClass(true);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                String method = invocation.getMethod().getName();
                if (!method.startsWith("send") && !method.startsWith("convertAndSend") && !method.startsWith("convertSendAndReceive")) {
                    return invocation.proceed();
                }
                Messages.Publish publish = Messages.publish("rabbitmq");
                if (publish == null) return invocation.proceed();
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
    }

    /** Before a message goes out, on the sending thread: its traceparent, and where it goes. */
    static final class Headers implements MessagePostProcessor {
        @Override
        public Message postProcessMessage(Message message) {
            return message;
        }

        @Override
        public Message postProcessMessage(Message message, Correlation correlation, String exchange, String routingKey) {
            Messages.Publish publish = Messages.publishing();
            if (publish == null) return message;
            // The default exchange routes by the queue's name: the queue is where it goes.
            publish.destination(exchange == null || exchange.isEmpty() ? routingKey : exchange);
            publish.attr("messaging.rabbitmq.destination.routing_key", routingKey);
            MessageProperties properties = message.getMessageProperties();
            if (properties.getHeader(W3c.HEADER) == null) {
                properties.setHeader(W3c.HEADER, publish.traceparent());
                properties.setHeader(Messages.SENT_BY, publish.sentBy());
            }
            return message;
        }
    }

    /** Each message a container delivers to its listener: a task trace around the listener. */
    static final class Deliveries implements MethodInterceptor {
        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Object[] args = invocation.getArguments();
            if (args.length < 2 || !(args[1] instanceof Message message)) return invocation.proceed();
            MessageProperties properties = message.getMessageProperties();
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("messaging.rabbitmq.destination.routing_key", properties.getReceivedRoutingKey());
            Object traceparent = properties.getHeader(W3c.HEADER);
            String queue = properties.getConsumerQueue() != null ? properties.getConsumerQueue() : properties.getReceivedRoutingKey();
            Object sentBy = properties.getHeader(Messages.SENT_BY);
            Trace trace = Messages.receive("rabbitmq", queue, traceparent == null ? null : traceparent.toString(),
                    sentBy == null ? null : sentBy.toString(), attrs);
            if (trace == null) return invocation.proceed();
            try {
                Object result = invocation.proceed();
                Messages.done(trace, null);
                return result;
            } catch (Throwable error) {
                Messages.done(trace, error);
                throw error;
            }
        }
    }
}
