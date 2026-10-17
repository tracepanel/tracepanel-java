package dev.tracepanel.spring;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * The handler that served the request, as a view span (com.shop.BookController.list) and the trace's code.view. An
 * async handler's span lasts until its answer is written: what it does on other threads meanwhile goes under it. Once
 * it ran, the signed-in user from Spring Security.
 */
final class ViewInterceptor implements HandlerInterceptor {
    private static final String SPAN = ViewInterceptor.class.getName() + ".span";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Trace trace = Tracepanel.current();
        // An async request's dispatch that writes the answer: the handler's span from the first one goes on.
        if (request.getAttribute(SPAN) != null) return true;
        if (trace != null && handler instanceof HandlerMethod method) {
            String name = method.getBeanType().getName() + "." + method.getMethod().getName();
            trace.attr("code.view", name);
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (route != null) request.setAttribute(TracepanelFilter.ROUTE, route);
            request.setAttribute(SPAN, trace.begin("view", name, Map.of("code.function", name)));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        Trace trace = Tracepanel.current();
        // Inside Spring Security's filters still: the request's user is in its context.
        if (trace != null && SecurityUsers.PRESENT) SecurityUsers.record(trace);
        if (trace != null && request.getAttribute(SPAN) instanceof Map<?, ?> span) {
            @SuppressWarnings("unchecked")
            Map<String, Object> view = (Map<String, Object>) span;
            trace.end(view, failure != null);
        }
    }
}
