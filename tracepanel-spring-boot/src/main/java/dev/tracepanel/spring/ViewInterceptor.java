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
 * The handler that served the request, as a view span and the trace's code.view, named as Java writes a method:
 * BookController#list. Its full name (com.shop.BookController.list) is the span's code.function, for the console's
 * tooltip. An async handler's span lasts until its answer is written: what it does on other threads meanwhile goes
 * under it. Before and after it, the signed-in user from Spring Security.
 */
final class ViewInterceptor implements HandlerInterceptor {
    private static final String SPAN = ViewInterceptor.class.getName() + ".span";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Trace trace = Tracepanel.current();
        // The user Spring Security signed in, before the handler: one that answers later (a suspend function, a
        // Callable) does so on a dispatch that may not have them.
        if (trace != null && SecurityUsers.PRESENT) SecurityUsers.record(trace);
        // An async request's dispatch that writes the answer: the handler's span from the first one goes on.
        if (request.getAttribute(SPAN) != null) return true;
        if (trace != null && handler instanceof HandlerMethod method) {
            Class<?> type = method.getBeanType();
            String name = type.getSimpleName() + "#" + method.getMethod().getName();
            String full = type.getName() + "." + method.getMethod().getName();
            trace.attr("code.view", name);
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (route != null) request.setAttribute(TracepanelFilter.ROUTE, route);
            request.setAttribute(SPAN, trace.begin("view", name, Map.of("code.function", full)));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        Trace trace = Tracepanel.current();
        // And after it, for a user it signed in.
        if (trace != null && SecurityUsers.PRESENT) SecurityUsers.record(trace);
        if (trace != null && request.getAttribute(SPAN) instanceof Map<?, ?> span) {
            @SuppressWarnings("unchecked")
            Map<String, Object> view = (Map<String, Object>) span;
            trace.end(view, failure != null);
        }
    }
}
