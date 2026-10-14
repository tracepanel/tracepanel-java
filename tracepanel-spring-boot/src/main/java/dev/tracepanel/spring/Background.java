package dev.tracepanel.spring;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The work an app does besides requests, each a trace of its own: an @Async method's run is a task (in the request's
 * call tree, the task.enqueue that queued it, which its trace links back to), an @Scheduled method's run and a
 * CommandLineRunner's or ApplicationRunner's are commands (the console's Jobs). Inside a trace (an @Async method
 * on a synchronous executor), the work is a step of it.
 */
final class Background implements MethodInterceptor {
    // Where the executor's thread finds what queued the task it runs: the queuing trace, its span and its role.
    private static final ThreadLocal<Queued> QUEUED = new ThreadLocal<>();

    private record Queued(String traceId, String spanId, Object role) {}

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Tracepanel tracepanel = Tracepanel.get();
        if (tracepanel == null) return invocation.proceed();
        Method method = invocation.getMethod();
        Class<?> type = invocation.getThis() == null ? method.getDeclaringClass() : AopUtils.getTargetClass(invocation.getThis());
        String name = type.getSimpleName() + "." + method.getName();
        Trace current = Tracepanel.current();
        if (current != null) return step(current, name, type, method, invocation);
        boolean task = AnnotatedElementUtils.hasAnnotation(method, Async.class) || AnnotatedElementUtils.hasAnnotation(type, Async.class);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("code.function", type.getName() + "." + method.getName());
        Queued queued = QUEUED.get();
        Trace trace;
        if (task) {
            attrs.put("task.system", "spring");
            attrs.put("task.name", name);
            attrs.put("task.trigger", queued == null ? "other" : "request");
            if (queued != null && queued.role() != null) attrs.put("service.role", queued.role());
            trace = tracepanel.begin("task", "task.run", name, attrs);
            if (queued != null) trace.link("enqueued_by", queued.traceId(), queued.spanId());
        } else {
            Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(method, Scheduled.class);
            if (scheduled != null) attrs.put("command.schedule", schedule(scheduled));
            attrs.put("command.trigger", scheduled != null ? "schedule" : "startup");
            trace = tracepanel.begin("command", name, attrs);
        }
        try {
            return invocation.proceed();
        } catch (Throwable error) {
            trace.error(error, tracepanel.stacks(), false);
            trace.failed();
            throw error;
        } finally {
            tracepanel.finish(trace);
        }
    }

    private static Object step(Trace trace, String name, Class<?> type, Method method, MethodInvocation invocation) throws Throwable {
        Map<String, Object> span = trace.begin("function", name, Map.of("code.function", type.getName() + "." + method.getName()));
        boolean failed = false;
        try {
            return invocation.proceed();
        } catch (Throwable error) {
            failed = true;
            throw error;
        } finally {
            trace.end(span, failed);
        }
    }

    /** How a scheduled method runs: its cron, or its rate or delay. */
    private static String schedule(Scheduled scheduled) {
        if (!scheduled.cron().isEmpty()) return "cron " + scheduled.cron();
        if (scheduled.fixedRate() > 0 || !scheduled.fixedRateString().isEmpty()) {
            return "every " + (scheduled.fixedRateString().isEmpty() ? scheduled.fixedRate() : scheduled.fixedRateString()) + " " + unit(scheduled);
        }
        return "after " + (scheduled.fixedDelayString().isEmpty() ? scheduled.fixedDelay() : scheduled.fixedDelayString()) + " " + unit(scheduled);
    }

    private static String unit(Scheduled scheduled) {
        return scheduled.timeUnit().name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Whether a method is background work this traces: @Async, @Scheduled, or a runner's run. */
    static boolean traced(Method method, Class<?> type) {
        if (AnnotatedElementUtils.hasAnnotation(method, Async.class) || AnnotatedElementUtils.hasAnnotation(type, Async.class)
                || AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) {
            return true;
        }
        return method.getName().equals("run")
                && (CommandLineRunner.class.isAssignableFrom(type) || ApplicationRunner.class.isAssignableFrom(type));
    }

    /**
     * The executor's decorator: an @Async call queued in a trace is a task.enqueue span of it, named after the
     * method (the proxy's frame on the queuing thread names it), and the task's run links back to it.
     */
    static final class Queuing implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Trace trace = Tracepanel.current();
            Tracepanel tracepanel = Tracepanel.get();
            if (trace == null || tracepanel == null) return runnable;
            String name = asyncMethod().orElse("async task");
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("task.system", "spring");
            attrs.put("task.name", name);
            attrs.put("code.origin", tracepanel.stacks().origin());
            String spanId = (String) trace.done("task.enqueue", name, attrs, Trace.nowMicros(), false).get("id");
            Queued queued = new Queued(trace.id(), spanId, trace.attr("service.role"));
            return () -> {
                QUEUED.set(queued);
                try {
                    runnable.run();
                } finally {
                    QUEUED.remove();
                }
            };
        }

        /** The @Async method being queued: the generated proxy's frame on this thread, Class.method. */
        private static Optional<String> asyncMethod() {
            return StackWalker.getInstance().walk(frames -> frames
                    .filter(frame -> frame.getClassName().contains("$$SpringCGLIB$$"))
                    .findFirst()
                    .map(frame -> {
                        String type = frame.getClassName().substring(0, frame.getClassName().indexOf("$$"));
                        return type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName();
                    }));
        }
    }
}
