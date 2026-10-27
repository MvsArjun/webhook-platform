package dev.relay.worker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Kept below Boot's default 30-second lifecycle phase timeout, including cancellation cleanup. */
@ConfigurationProperties("relay.worker")
public record WorkerShutdownSettings(@DefaultValue("10s") Duration shutdownGrace) {
    public WorkerShutdownSettings {
        if (shutdownGrace == null || shutdownGrace.compareTo(Duration.ofMillis(100)) < 0
                || shutdownGrace.compareTo(Duration.ofSeconds(20)) > 0) {
            throw new IllegalArgumentException("Worker shutdown grace must be between 100ms and 20s");
        }
    }
}
