package dev.relay.delivery.retry;

import java.time.Clock;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Retry scheduling is shared by API replay and worker completion/recovery. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetrySettings.class)
public class RetryConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock retryClock() {
        return Clock.systemUTC();
    }

    @Bean
    RetryPolicy retryPolicy(RetrySettings settings, Clock clock) {
        return new RetryPolicy(settings, clock, () -> ThreadLocalRandom.current().nextDouble());
    }
}
