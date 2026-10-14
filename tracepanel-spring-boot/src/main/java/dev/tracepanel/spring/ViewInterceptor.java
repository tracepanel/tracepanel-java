package dev.tracepanel.spring;

import dev.tracepanel.core.Trace;
import dev.tracepanel.core.Tracepanel;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** The handler that served the request, as a view span (com.shop.BookController.list) and the trace's code.view. */
final class ViewInterceptor implements HandlerInterceptor {
    private static final String SPAN = ViewInterceptor.class.getName() + ".span";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Trace trace = Tracepanel.current();
        if (trace != null && handler instanceof HandlerMethod method) {
            String name = method.getBeanType().getName() + "." + method.getMethod().getName();
            trace.attr("code.view", name);
            request.setAttribute(SPAN, trace.begin("view", name, Map.of("code.function", name)));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
        Trace trace = Tracepanel.current();
        if (trace != null && request.getAttribute(SPAN) instanceof Map<?, ?> span) {
            @SuppressWarnings("unchecked")
            Map<String, Object> view = (Map<String, Object>) span;
            trace.end(view, failure != null);
        }
    }
}
