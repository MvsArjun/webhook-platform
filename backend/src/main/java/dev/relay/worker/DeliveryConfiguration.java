package dev.relay.worker;

import dev.relay.delivery.DeliveryClaimService;
import dev.relay.delivery.DeliveryInputService;
import dev.relay.delivery.DeliveryProcessor;
import dev.relay.delivery.DeliveryLimits;
import dev.relay.delivery.http.HttpWebhookSender;
import dev.relay.delivery.http.LocalReceiverPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryLimits.class)
public class DeliveryConfiguration {
    @Bean
    LocalReceiverPolicy localReceiverPolicy(Environment environment) {
        return new LocalReceiverPolicy(environment.acceptsProfiles(Profiles.of("development")),
                environment.getProperty("relay.delivery.allow-local-receiver", Boolean.class, false),
                environment.getProperty("relay.delivery.local-receiver-url", ""));
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("worker")
    @EnableConfigurationProperties({WorkerSettings.class, WorkerShutdownSettings.class})
    static class WorkerConfiguration {
        @Bean(destroyMethod = "close")
        HttpWebhookSender webhookSender(WorkerSettings settings, LocalReceiverPolicy local, DeliveryLimits limits) {
            return new HttpWebhookSender(settings.concurrency(), settings.connectTimeout(), settings.dnsTimeout(),
                    local, limits.responseCaptureBytes());
        }

        @Bean
        DeliveryProcessor deliveryProcessor(DeliveryInputService inputs, DeliveryClaimService claims,
                                             HttpWebhookSender sender, WorkerSettings settings) {
            return new DeliveryProcessor(inputs, claims, sender, settings);
        }
    }
}
