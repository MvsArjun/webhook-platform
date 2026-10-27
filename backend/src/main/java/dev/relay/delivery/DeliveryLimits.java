package dev.relay.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("relay.delivery")
public record DeliveryLimits(@DefaultValue("4") int workspaceConcurrencyLimit,
                              @DefaultValue("4096") int responseCaptureBytes) {
    public DeliveryLimits {
        if (workspaceConcurrencyLimit < 1 || workspaceConcurrencyLimit > 32
                || responseCaptureBytes < 0 || responseCaptureBytes > 16384) {
            throw new IllegalArgumentException("Invalid delivery resource limits");
        }
    }
}
