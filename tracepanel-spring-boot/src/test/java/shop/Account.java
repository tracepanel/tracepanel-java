package shop;

import dev.tracepanel.core.Tracepanel;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/** The signed-in user: from Spring Security's context, or named by the app itself. */
@RestController
public class Account {
    @GetMapping("/account/signed-in")
    public String signedIn() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @GetMapping("/account/named")
    public String named() {
        Tracepanel.setUser("7", "bob");
        return "bob";
    }

    /** Spring Security's filter, as it puts the user in the context for the request, and takes them out after. */
    @Component
    static class SignIn extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!request.getRequestURI().equals("/account/signed-in")) {
                chain.doFilter(request, response);
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("ann", null, List.of()));
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }
    }
}
