package dev.relay.security;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import dev.relay.apikeys.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Created inside the bearer-only chain; intentionally not a servlet filter bean. */
final class ApiKeyAuthenticationFilter extends OncePerRequestFilter {
    private final ApiKeyService keys;

    ApiKeyAuthenticationFilter(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (!headers.isEmpty()) {
            String header = headers.getFirst();
            if (headers.size() != 1 || header.length() > 160
                    || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                unauthorized(response);
                return;
            }
            var principal = keys.authenticate(header.substring(7));
            if (principal.isEmpty()) {
                unauthorized(response);
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal.get(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_API_KEY"))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response) {
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
