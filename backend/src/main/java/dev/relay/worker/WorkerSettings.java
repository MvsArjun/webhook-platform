package dev.relay.worker;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("relay.worker")
public record WorkerSettings(@DefaultValue("4") int concurrency,
        @DefaultValue("250ms") Duration pollInterval,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("1s") Duration dnsTimeout,
        @DefaultValue("30s") Duration lease,
        @DefaultValue("1s") Duration leaseSafety,
        @DefaultValue("100") int recoveryBatch) {

    public WorkerSettings {
        if (concurrency < 1 || concurrency > 32 || recoveryBatch < 1 || recoveryBatch > 100
                || !between(pollInterval, 10, 10000) || !between(requestTimeout, 50, 120000)
                || !between(connectTimeout, 1, 120000) || !between(dnsTimeout, 1, 120000)
                || !between(leaseSafety, 50, 30000) || !between(lease, 100, 300000)
                || connectTimeout.compareTo(requestTimeout) > 0 || dnsTimeout.compareTo(requestTimeout) > 0
                || lease.compareTo(requestTimeout.plus(leaseSafety).plus(pollInterval)) <= 0) {
            throw new IllegalArgumentException("Invalid bounded worker settings or insufficient lease safety");
        }
    }

    private static boolean between(Duration value, long minimumMillis, long maximumMillis) {
        return value != null && value.compareTo(Duration.ofMillis(minimumMillis)) >= 0
                && value.compareTo(Duration.ofMillis(maximumMillis)) <= 0;
    }
}
