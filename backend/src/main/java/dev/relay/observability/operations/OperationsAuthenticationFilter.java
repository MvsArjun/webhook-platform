package dev.relay.observability.operations;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Constructed only inside the management chain; never registered as a servlet Filter bean. */
final class OperationsAuthenticationFilter extends OncePerRequestFilter {
    private final OperationsToken token;
    private final RequestMatcher probes;

    OperationsAuthenticationFilter(OperationsToken token, RequestMatcher probes) {
        this.token = token;
        this.probes = probes;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (probes.matches(request)) {
            chain.doFilter(request, response);
            return;
        }
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1) {
            unauthorized(response);
            return;
        }
        String header = headers.getFirst();
        if (header.length() != 50 || !header.regionMatches(true, 0, "Bearer ", 0, 7)
                || !token.authenticate(header.substring(7))) {
            unauthorized(response);
            return;
        }
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("operator", null,
                List.of(new SimpleGrantedAuthority("ROLE_OPERATIONS"))));
        SecurityContextHolder.setContext(context);
        chain.doFilter(request, response);
    }

    static void unauthorized(HttpServletResponse response) {
        response.setHeader("WWW-Authenticate", "Bearer");
        response.setHeader("Cache-Control", "no-store");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    }
}
