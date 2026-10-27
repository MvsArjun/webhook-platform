package dev.relay.observability.logging;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Correlation starts before body/security rejection; no client-supplied data becomes a log field. */
@Component
@Profile("!worker")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-Id";
    private static final Logger log = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE");
    // Only known server mapping templates are emitted. Unmapped/security-rejected requests use OTHER.
    private static final Set<String> ROUTES = Set.of(
            "/api/system/status", "/api/auth/csrf", "/api/auth/register", "/api/auth/me",
            "/api/workspaces", "/api/workspaces/{workspaceId}",
            "/api/workspaces/{workspaceId}/api-keys", "/api/workspaces/{workspaceId}/api-keys/{keyId}",
            "/api/workspaces/{workspaceId}/endpoints", "/api/workspaces/{workspaceId}/endpoints/{endpointId}",
            "/api/workspaces/{workspaceId}/endpoints/{endpointId}/signing-secret",
            "/api/workspaces/{workspaceId}/events", "/api/workspaces/{workspaceId}/events/{eventId}",
            "/api/workspaces/{workspaceId}/events/{eventId}/attempts",
            "/api/workspaces/{workspaceId}/events/{eventId}/replay",
            "/api/v1/workspace", "/api/v1/endpoints/{endpointId}/events", "/api/v1/events",
            "/api/v1/events/{eventId}", "/api/v1/events/{eventId}/attempts", "/api/v1/events/{eventId}/replay",
            "/actuator/health", "/actuator/health/{*path}", "/actuator/prometheus");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        UUID requestId = UUID.randomUUID();
        response.setHeader(HEADER, requestId.toString());
        long started = System.nanoTime();
        boolean failed = false;
        try (var ignored = SafeLogContext.request(requestId)) {
            try {
                chain.doFilter(request, response);
            } catch (IOException | ServletException | RuntimeException | Error failure) {
                failed = true;
                throw failure;
            } finally {
                // A disconnected client may already have received headers. Preserve that observed
                // status; an uncommitted uncaught failure will instead enter the container's 500 path.
                int status = failed && !response.isCommitted() ? Math.max(500, response.getStatus()) : response.getStatus();
                log.atInfo().addKeyValue("event_type", "request_completed")
                        .addKeyValue("method", method(request.getMethod()))
                        .addKeyValue("route_template", route(request))
                        .addKeyValue("status", status)
                        .addKeyValue("aborted", failed)
                        .addKeyValue("duration_ms", Math.max(0, Duration.ofNanos(System.nanoTime() - started).toMillis()))
                        .log("HTTP request completed");
            }
        }
    }

    private static String method(String value) {
        return value != null && METHODS.contains(value) ? value : "OTHER";
    }

    private static String route(HttpServletRequest request) {
        Object value = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return value instanceof String template && ROUTES.contains(template) ? template : "OTHER";
    }
}
