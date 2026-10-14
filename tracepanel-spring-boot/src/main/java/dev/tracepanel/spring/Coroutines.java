package dev.tracepanel.spring;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import java.lang.reflect.Method;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.ThreadContextElementKt;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.core.KotlinDetector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;

/**
 * A Kotlin controller's suspend functions, in their request's trace on whichever thread they resume on: the trace
 * lives in a thread local, and a coroutine moves between threads (withContext(Dispatchers.IO), a delay). Called
 * through Spring's proxy, a suspend function runs in the context of the continuation it is given: this one adds an
 * element that puts the trace on each thread the coroutine resumes on, and takes it off after.
 */
final class Coroutines implements MethodInterceptor {
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Trace trace = Tracepanel.current();
        Object[] args = invocation.getArguments();
        if (trace != null && args.length > 0 && args[args.length - 1] instanceof Continuation<?> continuation) {
            args[args.length - 1] = new Traced<>(continuation, trace);
        }
        return invocation.proceed();
    }

    /** A controller's suspend function: what this applies to. */
    static boolean traced(Method method, Class<?> type) {
        return KotlinDetector.isSuspendingFunction(method) && AnnotatedElementUtils.hasAnnotation(type, Controller.class);
    }

    /** The caller's continuation, with the trace in its context. */
    private static final class Traced<T> implements Continuation<T> {
        private final Continuation<T> delegate;
        private final CoroutineContext context;

        Traced(Continuation<T> delegate, Trace trace) {
            this.delegate = delegate;
            this.context = delegate.getContext().plus(ThreadContextElementKt.asContextElement(Tracepanel.holder(), trace));
        }

        @Override
        public CoroutineContext getContext() {
            return context;
        }

        @Override
        public void resumeWith(Object result) {
            delegate.resumeWith(result);
        }
    }
}
