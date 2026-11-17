package dev.tracepanel.micronaut;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.propagation.ThreadPropagatedContextElement;

/** The request's trace on whichever thread Micronaut runs its work: its executors, Reactor's schedulers. */
record TraceContext(Trace trace) implements ThreadPropagatedContextElement<Trace> {
    @Override
    public Trace updateThreadContext() {
        return Tracepanel.bind(trace);
    }

    @Override
    public void restoreThreadContext(@Nullable Trace previous) {
        Tracepanel.bind(previous);
    }
}
