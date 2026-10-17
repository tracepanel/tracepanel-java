package dev.tracepanel.spring;

import dev.tracepanel.core.Trace;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.ClassUtils;

/**
 * The signed-in user from Spring Security, when the app has it: the authenticated principal's name, as user.id and
 * user.username. A user the app named itself (Tracepanel.setUser) stays.
 */
final class SecurityUsers {
    static final boolean PRESENT =
            ClassUtils.isPresent("org.springframework.security.core.context.SecurityContextHolder", SecurityUsers.class.getClassLoader());

    private SecurityUsers() {}

    static void record(Trace trace) {
        if (trace.attr("user.id") != null) return;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) return;
        String name = authentication.getName();
        if (name != null && !name.isEmpty()) trace.user(name, name);
    }
}
