package dev.relay.observability.metrics;

import java.time.Clock;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("!worker")
@ConditionalOnProperty(name = "relay.operations.enabled", havingValue = "true")
@EnableConfigurationProperties(MetricsSettings.class)
public class DurableMetricsConfiguration {
    @Bean
    MetricsSnapshotRepository metricsSnapshotRepository(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        return new MetricsSnapshotRepository(jdbc, transactions);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    CachedDeliveryMetrics cachedDeliveryMetrics(MetricsSnapshotRepository repository, MeterRegistry registry,
                                                 MetricsSettings settings, Clock clock) {
        return new CachedDeliveryMetrics(repository, registry, settings, clock);
    }
}
