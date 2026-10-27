package dev.relay.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Rejects oversized management requests before CSRF, authentication or JSON deserialization. */
@Component
@Profile("!worker")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ManagementBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 16384;
    private static final Pattern EVENT_INGESTION = Pattern.compile("/api/v1/endpoints/[^/]+/events");
    private static final byte[] TOO_LARGE = "{\"error\":\"request_too_large\"}".getBytes(StandardCharsets.UTF_8);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Match the container's routed path rather than a raw URI that may retain encoded
        // characters or dot segments which the servlet mapper has already normalized.
        String path = request.getServletPath();
        if (request.getPathInfo() != null) path += request.getPathInfo();
        return !(path.equals("/api") || path.startsWith("/api/"))
                || ("POST".equalsIgnoreCase(request.getMethod()) && EVENT_INGESTION.matcher(path).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            reject(response);
            return;
        }
        if (!json(request.getContentType())) {
            // Never consume forms: Tomcat enforces its configured form-post limit while its
            // normal parameter parser remains available to Spring Security's form-login filter.
            chain.doFilter(request, response);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (body.length > MAX_BYTES) {
            reject(response);
            return;
        }
        chain.doFilter(new CachedJsonRequest(request, body), response);
    }

    private static boolean json(String contentType) {
        if (contentType == null) return false;
        try {
            MediaType type = MediaType.parseMediaType(contentType);
            String subtype = type.getSubtype().toLowerCase(java.util.Locale.ROOT);
            return "application".equalsIgnoreCase(type.getType())
                    && (subtype.equals("json") || subtype.endsWith("+json"));
        } catch (InvalidMediaTypeException invalid) {
            return false;
        }
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentLength(TOO_LARGE.length);
        response.getOutputStream().write(TOO_LARGE);
    }

    private static final class CachedJsonRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedJsonRequest(HttpServletRequest original, byte[] body) {
            super(original);
            this.body = body;
        }

        @Override public int getContentLength() { return body.length; }
        @Override public long getContentLengthLong() { return body.length; }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] buffer, int offset, int length) { return input.read(buffer, offset, length); }
                @Override public int available() { return input.available(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("Only blocking request body reads are supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String charset = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    charset == null ? StandardCharsets.UTF_8.name() : charset));
        }
    }
}
