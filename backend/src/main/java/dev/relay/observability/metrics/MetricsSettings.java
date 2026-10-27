package dev.relay.observability.metrics;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("relay.operations.metrics")
public record MetricsSettings(@DefaultValue("5s") Duration refreshInterval) {
    public MetricsSettings {
        if (refreshInterval == null || refreshInterval.compareTo(Duration.ofSeconds(1)) < 0
                || refreshInterval.compareTo(Duration.ofSeconds(60)) > 0) {
            throw new IllegalArgumentException("Metrics refresh interval must be between 1s and 60s");
        }
    }
}
