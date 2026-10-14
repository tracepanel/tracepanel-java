package dev.tracepanel.spring;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * What a handler threw, before Spring answers it (@ExceptionHandler, the error page): the trace keeps it, and the
 * request's status says later whether it failed. An exception that is a 4xx answer itself isn't an error.
 */
final class ErrorRecorder implements HandlerExceptionResolver, Ordered {
    private final Tracepanel tracepanel;

    ErrorRecorder(Tracepanel tracepanel) {
        this.tracepanel = tracepanel;
    }

    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        Trace trace = Tracepanel.current();
        if (trace != null && !isResponse(failure)) trace.error(failure, tracepanel.stacks(), false);
        // Recorded only: the app's own resolvers answer it.
        return null;
    }

    /**
     * An exception that is the answer, below 500: ResponseStatusException (a missing record's 404), any ErrorResponse,
     * or an exception marked @ResponseStatus. The response says what happened, as in the other SDKs.
     */
    static boolean isResponse(Exception failure) {
        if (failure instanceof ErrorResponse response) return response.getStatusCode().value() < 500;
        ResponseStatus status = AnnotatedElementUtils.findMergedAnnotation(failure.getClass(), ResponseStatus.class);
        return status != null && status.code().value() < 500;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
