package dev.relay.observability.logging;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.io.IOException;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestCorrelationFilterTest {
    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @AfterEach
    void clearContext() { MDC.clear(); }

    @Test
    void generatesFreshIdsAndLogsOnlyTheResolvedTemplateWithScopedContext() throws Exception {
        var logs = new EventCapture(RequestCorrelationFilter.class);
        try (logs) {
            MDC.put("outer", "private-caller-marker");
            var request = new MockHttpServletRequest("GET", "/api/v1/events/private-path-marker");
            request.setQueryString("secret=private-query-marker");
            request.addHeader("X-Request-Id", "private-header-marker");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (current, result) -> {
                assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("request_id");
                assertThat(MDC.get("request_id")).isEqualTo(response.getHeader(RequestCorrelationFilter.HEADER));
                current.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/events/{eventId}");
                ((MockHttpServletResponse) result).setStatus(404);
            });
            String id = response.getHeader(RequestCorrelationFilter.HEADER);
            assertThat(UUID.fromString(id).toString()).isEqualTo(id);
            assertThat(MDC.getCopyOfContextMap()).containsExactlyEntriesOf(Map.of("outer", "private-caller-marker"));
            var event = logs.events.getFirst();
            assertThat(event.getMDCPropertyMap()).containsExactlyEntriesOf(Map.of("request_id", id));
            assertThat(fields(event)).containsEntry("event_type", "request_completed")
                    .containsEntry("method", "GET").containsEntry("route_template", "/api/v1/events/{eventId}")
                    .containsEntry("status", 404).containsEntry("aborted", false)
                    .containsOnlyKeys("event_type", "method", "route_template", "status", "aborted", "duration_ms");
            assertThat(event.getFormattedMessage()).doesNotContain("private-");

            var nextResponse = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("POST", "/private-other-marker"), nextResponse,
                    (current, result) -> ((MockHttpServletResponse) result).setStatus(403));
            assertThat(nextResponse.getHeader(RequestCorrelationFilter.HEADER)).isNotEqualTo(id);
            assertThat(logs.events).hasSize(2);
            assertThat(fields(logs.events.getLast())).containsEntry("route_template", "OTHER").containsEntry("status", 403);
        }
    }

    @Test
    void exceptionHasOneSafe500CompletionAndAlwaysRestoresMdc() throws Exception {
        try (var logs = new EventCapture(RequestCorrelationFilter.class)) {
            var request = new MockHttpServletRequest("HOSTILE-METHOD-MARKER", "/private-path-marker");
            request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/private-template-marker");
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> filter.doFilter(request, response, (current, result) -> {
                throw new ServletException("private-exception-marker");
            })).isInstanceOf(ServletException.class);
            assertThat(logs.events).hasSize(1);
            var event = logs.events.getFirst();
            assertThat(fields(event)).containsEntry("method", "OTHER").containsEntry("route_template", "OTHER")
                    .containsEntry("status", 500);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getFormattedMessage()).doesNotContain("private-");
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
            assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isNotBlank();
        }
    }

    @Test
    void disconnectedClientAfterCommittedHeadersKeepsTheObservedStatusAndMarksAborted() throws Exception {
        try (var logs = new EventCapture(RequestCorrelationFilter.class)) {
            var response = new MockHttpServletResponse();
            assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/system/status"),
                    response, (current, result) -> {
                        response.setStatus(200);
                        response.flushBuffer();
                        throw new IOException("private-disconnect-marker");
                    })).isInstanceOf(IOException.class);
            assertThat(logs.events).hasSize(1);
            assertThat(fields(logs.events.getFirst())).containsEntry("status", 200).containsEntry("aborted", true);
            assertThat(logs.events.getFirst().getThrowableProxy()).isNull();
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        var result = new java.util.HashMap<String, Object>();
        event.getKeyValuePairs().forEach(pair -> result.put(pair.key, pair.value));
        return result;
    }

    private static final class EventCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final Logger logger;
        private final ArrayList<ILoggingEvent> events = new ArrayList<>();

        private EventCapture(Class<?> type) {
            logger = (Logger) LoggerFactory.getLogger(type);
            start();
            logger.addAppender(this);
        }

        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            events.add(event);
        }

        @Override public void close() { logger.detachAppender(this); stop(); }
    }
}
