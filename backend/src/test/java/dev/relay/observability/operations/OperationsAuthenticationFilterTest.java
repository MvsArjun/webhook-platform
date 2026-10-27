package dev.relay.observability.operations;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsAuthenticationFilterTest {
    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void exactBearerAuthenticatesOnlyAnOperatorAndNeverRetainsTheRawCredential() throws Exception {
        String raw = OperationsTokenTest.token((byte) 17);
        var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "bEaReR " + raw);
        var response = new MockHttpServletResponse();
        var continued = new AtomicBoolean();
        enabled(raw).doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));
        assertThat(continued).isTrue();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getName()).isEqualTo("operator");
        assertThat(authentication.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OPERATIONS");
        assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.toString()).doesNotContain(raw);
    }

    @Test
    void malformedMultipleMissingAndOversizedHeadersAreRejectedBeforeContinuing() throws Exception {
        String raw = OperationsTokenTest.token((byte) 17);
        for (var headers : List.of(List.<String>of(), List.of("Basic " + raw), List.of("Bearer " + raw + " "),
                List.of("Bearer " + raw, "Bearer " + raw), List.of("Bearer " + "x".repeat(4096)),
                List.of("Bearer " + raw + ", Bearer " + raw), List.of("Bearer " + OperationsTokenTest.token((byte) 18)))) {
            var request = new MockHttpServletRequest("GET", "/actuator/prometheus");
            headers.forEach(header -> request.addHeader("Authorization", header));
            var response = new MockHttpServletResponse();
            var continued = new AtomicBoolean();
            enabled(raw).doFilter(request, response, (ignoredRequest, ignoredResponse) -> continued.set(true));
            assertThat(continued).isFalse();
            assertThat(response.getStatus()).isEqualTo(401);
            assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
            assertThat(response.getContentAsString()).isEmpty();
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Test
    void publicProbesDoNotRequireOrParseAnOperatorCredential() throws Exception {
        var filter = new OperationsAuthenticationFilter(OperationsToken.from(new MockEnvironment()),
                request -> request.getRequestURI().equals("/actuator/health/readiness"));
        var request = new MockHttpServletRequest("GET", "/actuator/health/readiness");
        request.addHeader("Authorization", "malformed");
        var continued = new AtomicBoolean();
        filter.doFilter(request, new MockHttpServletResponse(), (ignoredRequest, ignoredResponse) -> continued.set(true));
        assertThat(continued).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private static OperationsAuthenticationFilter enabled(String raw) {
        return new OperationsAuthenticationFilter(OperationsToken.from(new MockEnvironment()
                .withProperty("relay.operations.enabled", "true").withProperty("relay.operations.token", raw)), request -> false);
    }
}
