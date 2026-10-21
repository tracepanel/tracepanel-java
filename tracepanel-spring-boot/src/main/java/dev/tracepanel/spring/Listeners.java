package dev.tracepanel.spring;

import dev.tracepanel.core.LiveTasks;
import dev.tracepanel.core.Messages;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * The @KafkaListener or @RabbitListener method that handles a message names its trace: Class.method. An app with
 * one sends task events (the messages it handles), and says so from the start.
 */
final class Listeners implements MethodInterceptor {
    // Each listener annotation the app has, with the system of the messages it handles.
    private static final Map<Class<? extends Annotation>, String> ANNOTATIONS = annotations(Map.of(
            "org.springframework.kafka.annotation.KafkaListener", "kafka",
            "org.springframework.amqp.rabbit.annotation.RabbitListener", "rabbitmq"));

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Class<?> type = invocation.getThis() == null ? method.getDeclaringClass() : AopUtils.getTargetClass(invocation.getThis());
        Messages.handledBy(type.getSimpleName() + "." + method.getName());
        return invocation.proceed();
    }

    static boolean traced(Method method, Class<?> type) {
        for (Map.Entry<Class<? extends Annotation>, String> annotation : ANNOTATIONS.entrySet()) {
            if (AnnotatedElementUtils.hasAnnotation(method, annotation.getKey())) {
                LiveTasks.expect(annotation.getValue());
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<? extends Annotation>, String> annotations(Map<String, String> names) {
        Map<Class<? extends Annotation>, String> found = new LinkedHashMap<>();
        names.forEach((name, system) -> {
            try {
                found.put((Class<? extends Annotation>) Class.forName(name, false, Listeners.class.getClassLoader()), system);
            } catch (ClassNotFoundException | LinkageError e) {
                // Not in the app: nothing of it to name.
            }
        });
        return found;
    }
}
