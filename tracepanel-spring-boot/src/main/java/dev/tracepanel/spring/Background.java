package dev.tracepanel.spring;

import dev.tracepanel.core.LiveTasks;
import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import dev.tracepanel.core.Ulid;
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
 *
 * <p>The console's Live (the Tasks page's Now) shows them as they go: an @Async method's run is queued from its
 * task.enqueue on, then runs (system spring); an @Scheduled method's runs (system scheduled). A runner's isn't: it is
 * the app starting.
 */
final class Background implements MethodInterceptor {
    static final String ASYNC = "spring";
    static final String SCHEDULED = "scheduled";
    // Where the executor's thread finds what queued the task it runs: the queuing trace, its span and its role.
    private static final ThreadLocal<Queued> QUEUED = new ThreadLocal<>();

    private record Queued(String traceId, String spanId, Object role, String taskId) {}

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Tracepanel tracepanel = Tracepanel.get();
        if (tracepanel == null) return invocation.proceed();
        Method method = invocation.getMethod();
        Class<?> type = invocation.getThis() == null ? method.getDeclaringClass() : AopUtils.getTargetClass(invocation.getThis());
        String name = type.getSimpleName() + "." + method.getName();
        Trace current = Tracepanel.current();
        if (current != null) return step(current, name, type, method, invocation);
        boolean task = async(method, type);
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("code.function", type.getName() + "." + method.getName());
        Queued queued = QUEUED.get();
        Trace trace;
        // In the console's Live while it runs: an @Async method's run (spring), an @Scheduled one's (scheduled).
        String live = null;
        String liveId = queued != null ? queued.taskId() : Ulid.next();
        if (task) {
            live = ASYNC;
            attrs.put("task.system", ASYNC);
            attrs.put("task.name", name);
            attrs.put("task.id", liveId);
            attrs.put("task.worker", tracepanel.tasks().worker());
            attrs.put("task.trigger", queued == null ? "other" : "request");
            if (queued != null && queued.role() != null) attrs.put("service.role", queued.role());
            trace = tracepanel.begin("task", "task.run", name, attrs);
            if (queued != null) trace.link("enqueued_by", queued.traceId(), queued.spanId());
        } else {
            Scheduled scheduled = AnnotatedElementUtils.findMergedAnnotation(method, Scheduled.class);
            if (scheduled != null) {
                live = SCHEDULED;
                attrs.put("command.schedule", schedule(scheduled));
            }
            attrs.put("command.trigger", scheduled != null ? "schedule" : "startup");
            trace = tracepanel.begin("command", name, attrs);
        }
        if (live != null) tracepanel.tasks().started(live, liveId, name, null, 0);
        boolean failed = false;
        try {
            return invocation.proceed();
        } catch (Throwable error) {
            failed = true;
            trace.error(error, tracepanel.stacks(), false);
            trace.failed();
            throw error;
        } finally {
            tracepanel.finish(trace);
            if (live != null) tracepanel.tasks().finished(live, liveId, failed ? "FAILURE" : "SUCCESS");
        }
    }

    private static boolean async(Method method, Class<?> type) {
        return AnnotatedElementUtils.hasAnnotation(method, Async.class) || AnnotatedElementUtils.hasAnnotation(type, Async.class);
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

    /**
     * Whether a method is background work this traces: @Async, @Scheduled, or a runner's run. An app with @Async or
     * @Scheduled methods sends task events, and says so from the start.
     */
    static boolean traced(Method method, Class<?> type) {
        if (async(method, type)) {
            LiveTasks.expect(ASYNC);
            return true;
        }
        if (AnnotatedElementUtils.hasAnnotation(method, Scheduled.class)) {
            LiveTasks.expect(SCHEDULED);
            return true;
        }
        return method.getName().equals("run")
                && (CommandLineRunner.class.isAssignableFrom(type) || ApplicationRunner.class.isAssignableFrom(type));
    }

    /**
     * The executor's decorator: an @Async call queued in a trace is a task.enqueue span of it, named after the
     * method (the proxy's frame on the queuing thread names it), and the task's run links back to it. Other work a
     * trace hands to the executor (an async request's Callable, a CompletableFuture's step) runs in that trace.
     */
    static final class Queuing implements TaskDecorator {
        @Override
        public Runnable decorate(Runnable runnable) {
            Trace trace = Tracepanel.current();
            Tracepanel tracepanel = Tracepanel.get();
            if (trace == null || tracepanel == null) return runnable;
            Optional<String> method = asyncMethod();
            if (method.isEmpty()) {
                // Not an @Async method: the request's own work on another thread (Spring MVC's Callable), in its trace.
                return () -> {
                    Trace before = Tracepanel.bind(trace);
                    try {
                        runnable.run();
                    } finally {
                        Tracepanel.bind(before);
                    }
                };
            }
            String name = method.get();
            String taskId = Ulid.next();
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("task.system", ASYNC);
            attrs.put("task.name", name);
            attrs.put("task.id", taskId);
            attrs.put("code.origin", tracepanel.stacks().origin());
            String spanId = (String) trace.done("task.enqueue", name, attrs, Trace.nowMicros(), false).get("id");
            // Queued in Live until the executor runs it.
            tracepanel.tasks().enqueued(ASYNC, taskId, name, null, trace.id(), spanId);
            Queued queued = new Queued(trace.id(), spanId, trace.attr("service.role"), taskId);
            return () -> {
                // Run inside a trace (a synchronous executor), it is a step of it: no task of its own in Live.
                boolean inline = Tracepanel.current() != null;
                boolean failed = true;
                QUEUED.set(queued);
                try {
                    runnable.run();
                    failed = false;
                } finally {
                    QUEUED.remove();
                    if (inline) tracepanel.tasks().finished(ASYNC, taskId, failed ? "FAILURE" : "SUCCESS");
                }
            };
        }

        /**
         * The @Async method being queued, as Class.method: Spring's AsyncExecutionInterceptor submits it, on this
         * thread, called through the bean's proxy. Without that interceptor on the stack, the work isn't an @Async
         * method's, whatever proxies (@Transactional, @Cacheable) the code that hands it over runs through.
         */
        private static Optional<String> asyncMethod() {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(frames -> {
                boolean submitted = false;
                for (StackWalker.StackFrame frame : (Iterable<StackWalker.StackFrame>) frames::iterator) {
                    if (!submitted) {
                        submitted = ASYNC_INTERCEPTOR.equals(frame.getClassName()) && frame.getMethodName().equals("invoke");
                        continue;
                    }
                    Class<?> proxy = frame.getDeclaringClass();
                    if (proxy.getName().contains("$$SpringCGLIB$$")) {
                        return Optional.of(proxy.getSuperclass().getSimpleName() + "." + frame.getMethodName());
                    }
                    if (java.lang.reflect.Proxy.isProxyClass(proxy)) {
                        Class<?>[] interfaces = proxy.getInterfaces();
                        String type = interfaces.length > 0 ? interfaces[0].getSimpleName() : proxy.getSimpleName();
                        return Optional.of(type + "." + frame.getMethodName());
                    }
                }
                return submitted ? Optional.of("async") : Optional.empty();
            });
        }

        private static final String ASYNC_INTERCEPTOR = "org.springframework.aop.interceptor.AsyncExecutionInterceptor";
    }
}
