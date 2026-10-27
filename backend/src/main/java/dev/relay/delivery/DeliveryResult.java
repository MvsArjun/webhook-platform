package dev.relay.delivery;

import java.util.Set;

/** Bounded result data. The preview is customer-visible untrusted content and must never be logged. */
public record DeliveryResult(CompletionOutcome outcome, Integer httpStatus, String failureCode,
                             long durationMillis, String responsePreview, boolean responseTruncated) {
    private static final Set<String> FAILURE_CODES = Set.of("HTTP_STATUS", "REQUEST_TIMEOUT",
            "CONNECT_TIMEOUT", "DNS_ERROR", "DESTINATION_BLOCKED", "NETWORK_ERROR",
            "INTERNAL_ERROR", "RESOURCE_LIMIT");

    public DeliveryResult {
        if (outcome == null || durationMillis < 0 || (httpStatus != null && (httpStatus < 100 || httpStatus > 599))
                || (outcome == CompletionOutcome.SUCCEEDED
                    && (httpStatus == null || httpStatus < 200 || httpStatus >= 300 || failureCode != null))
                || (outcome == CompletionOutcome.FAILED
                    && (failureCode == null || !FAILURE_CODES.contains(failureCode)
                        || (httpStatus != null && httpStatus >= 200 && httpStatus < 300)))
                || (httpStatus == null && (responsePreview != null || responseTruncated))
                || (responsePreview != null && (responsePreview.length() > 16384
                    || responsePreview.indexOf('\0') >= 0
                    || responsePreview.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 49152))) {
            throw new IllegalArgumentException("Invalid delivery result");
        }
    }

    public DeliveryResult(CompletionOutcome outcome, Integer httpStatus, String failureCode, long durationMillis) {
        this(outcome, httpStatus, failureCode, durationMillis, null, false);
    }

    @Override
    public String toString() {
        return "DeliveryResult[outcome=" + outcome + ", httpStatus=" + httpStatus
                + ", failureCode=" + failureCode + ", durationMillis=" + durationMillis
                + ", responsePreview=REDACTED, responseTruncated=" + responseTruncated + "]";
    }
}
