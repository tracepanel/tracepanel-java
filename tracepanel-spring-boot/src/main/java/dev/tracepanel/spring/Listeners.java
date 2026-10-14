package dev.tracepanel.spring;

import dev.tracepanel.core.Messages;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotatedElementUtils;

/** The @KafkaListener or @RabbitListener method that handles a message names its trace: Class.method. */
final class Listeners implements MethodInterceptor {
    private static final List<Class<? extends Annotation>> ANNOTATIONS = annotations(
            "org.springframework.kafka.annotation.KafkaListener", "org.springframework.amqp.rabbit.annotation.RabbitListener");

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();
        Class<?> type = invocation.getThis() == null ? method.getDeclaringClass() : AopUtils.getTargetClass(invocation.getThis());
        Messages.handledBy(type.getSimpleName() + "." + method.getName());
        return invocation.proceed();
    }

    static boolean traced(Method method, Class<?> type) {
        for (Class<? extends Annotation> annotation : ANNOTATIONS) {
            if (AnnotatedElementUtils.hasAnnotation(method, annotation)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Annotation>> annotations(String... names) {
        List<Class<? extends Annotation>> found = new ArrayList<>();
        for (String name : names) {
            try {
                found.add((Class<? extends Annotation>) Class.forName(name, false, Listeners.class.getClassLoader()));
            } catch (ClassNotFoundException | LinkageError e) {
                // Not in the app: nothing of it to name.
            }
        }
        return found;
    }
}
