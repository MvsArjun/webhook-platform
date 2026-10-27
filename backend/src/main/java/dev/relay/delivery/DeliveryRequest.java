package dev.relay.delivery;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

import dev.relay.delivery.signing.SigningKey;

/** Transport input; no API/browser credentials or fencing token. Never log its signing material. */
public record DeliveryRequest(URI url, byte[] payload, UUID eventId, UUID deliveryId,
                              UUID attemptId, long generation, SigningKey signingKey) {
    public DeliveryRequest {
        Objects.requireNonNull(url);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(eventId);
        Objects.requireNonNull(deliveryId);
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(signingKey);
        if (payload.length > 262144 || generation < 1) {
            throw new IllegalArgumentException("Invalid delivery request");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() { return payload.clone(); }

    @Override
    public String toString() {
        return "DeliveryRequest[eventId=" + eventId + ", deliveryId=" + deliveryId
                + ", attemptId=" + attemptId + ", generation=" + generation + "]";
    }
}
