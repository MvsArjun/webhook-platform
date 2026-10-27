package dev.relay.delivery.retry;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("relay.retry")
public record RetrySettings(@DefaultValue("5") int maxAttempts,
                            @DefaultValue("1s") Duration baseDelay,
                            @DefaultValue("5m") Duration maxDelay) {
    public RetrySettings {
        if (maxAttempts < 1 || maxAttempts > 20
                || baseDelay == null || baseDelay.compareTo(Duration.ofMillis(1)) < 0
                || baseDelay.compareTo(Duration.ofHours(1)) > 0
                || maxDelay == null || maxDelay.compareTo(baseDelay) < 0
                || maxDelay.compareTo(Duration.ofHours(24)) > 0) {
            throw new IllegalArgumentException("Invalid bounded retry settings");
        }
    }
}
