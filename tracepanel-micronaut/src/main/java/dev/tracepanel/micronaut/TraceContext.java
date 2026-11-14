package dev.tracepanel.micronaut;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.propagation.ThreadPropagatedContextElement;

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
