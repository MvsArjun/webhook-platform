package dev.relay.delivery.signing;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import dev.relay.delivery.DeliveryRequest;

/** Versioned wire signature. Metadata is canonical text; the final payload is its original bytes. */
public final class WebhookSignature {
    private WebhookSignature() { }

    public static SignedHeaders sign(DeliveryRequest request, Instant now) {
        if (request == null || now == null || request.signingKey() == null) {
            throw new IllegalArgumentException("Signing inputs are required");
        }
        String timestamp = Long.toString(now.getEpochSecond());
        return new SignedHeaders(timestamp, "v1=" + HexFormat.of().formatHex(mac(request, timestamp)));
    }

    public static boolean verify(DeliveryRequest request, String timestamp, String signature, Instant now) {
        if (request == null || now == null || request.signingKey() == null || timestamp == null || signature == null
                || !timestamp.matches("0|[1-9][0-9]{0,18}") || !signature.matches("v1=[0-9a-f]{64}")) return false;
        final long seconds;
        try { seconds = Long.parseLong(timestamp); }
        catch (NumberFormatException invalid) { return false; }
        long current = now.getEpochSecond();
        if (seconds < current - 300 || seconds > current + 300) return false;
        return MessageDigest.isEqual(mac(request, timestamp), HexFormat.of().parseHex(signature.substring(3)));
    }

    private static byte[] mac(DeliveryRequest request, String timestamp) {
        String prefix = "relay.webhook.v1\n" + timestamp + "\n" + request.eventId() + "\n"
                + request.deliveryId() + "\n" + request.attemptId() + "\n" + request.generation() + "\n";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(request.signingKey().bytes(), "HmacSHA256"));
            mac.update(prefix.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(request.payload());
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("Webhook signing is unavailable");
        }
    }

    public record SignedHeaders(String timestamp, String signature) {
        @Override public String toString() { return "SignedHeaders[timestamp=" + timestamp + ", signature=REDACTED]"; }
    }
}
