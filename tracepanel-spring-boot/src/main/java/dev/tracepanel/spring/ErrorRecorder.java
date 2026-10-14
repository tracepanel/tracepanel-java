package dev.tracepanel.spring;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * What a handler threw, before Spring answers it (@ExceptionHandler, a 404 for ResponseStatusException, the error
 * page): the trace keeps it, and the request's status says later whether it failed.
 */
final class ErrorRecorder implements HandlerExceptionResolver, Ordered {
    private final Tracepanel tracepanel;

    ErrorRecorder(Tracepanel tracepanel) {
        this.tracepanel = tracepanel;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        Trace trace = Tracepanel.current();
        if (trace != null) trace.error(failure, tracepanel.stacks(), false);
        // Recorded only: the app's own resolvers answer it.
        return null;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
