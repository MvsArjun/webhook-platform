package dev.relay.delivery;

import java.time.Duration;

public interface WebhookSender {
    DeliveryResult send(DeliveryRequest request, Duration budget);
}
