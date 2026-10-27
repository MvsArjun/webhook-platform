package dev.relay.delivery.signing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import dev.relay.delivery.DeliveryRequest;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookSignatureTest {
    private static final Instant NOW = Instant.ofEpochSecond(1_700_000_000);
    private static final UUID EVENT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID DELIVERY = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID ATTEMPT = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final byte[] PAYLOAD = "{\"amount\":42,\"label\":\"café\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void signingKeyCopiesBothBoundariesAndRedactsDiagnosticText() {
        byte[] bytes = bytes();
        var key = new SigningKey(bytes);
        bytes[0] = 99;
        byte[] obtained = key.bytes();
        obtained[1] = 99;
        assertThat(key.bytes()).isEqualTo(bytes());
        assertThat(key.toString()).isEqualTo("SigningKey[REDACTED]");
        assertThatThrownBy(() -> new SigningKey(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SigningKey(new byte[31])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SigningKey(new byte[33])).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactUtf8CanonicalMessageMatchesIndependentHmacVectorAndTimestampWindow() {
        var request = request(EVENT, DELIVERY, ATTEMPT, 7, PAYLOAD, new SigningKey(bytes()));
        var signed = WebhookSignature.sign(request, NOW);
        assertThat(signed.timestamp()).isEqualTo("1700000000");
        // Independently computed using .NET HMACSHA256 over the documented canonical bytes.
        assertThat(signed.signature()).isEqualTo("v1=0967cbd5857ca9bb8f10c453a4b6953912c7e1618e0226fb11881ccf417c05e2");
        assertThat(WebhookSignature.verify(request, signed.timestamp(), signed.signature(), NOW)).isTrue();
        assertThat(WebhookSignature.verify(request, signed.timestamp(), signed.signature(), NOW.plusSeconds(300))).isTrue();
        assertThat(WebhookSignature.verify(request, signed.timestamp(), signed.signature(), NOW.minusSeconds(300))).isTrue();
        assertThat(WebhookSignature.verify(request, signed.timestamp(), signed.signature(), NOW.plusSeconds(301))).isFalse();
        assertThat(WebhookSignature.verify(request, signed.timestamp(), signed.signature(), NOW.minusSeconds(301))).isFalse();
        assertThat(signed.toString()).doesNotContain(signed.signature());
    }

    @Test
    void metadataPayloadKeyAndWireEncodingTamperingCannotVerify() {
        var request = request(EVENT, DELIVERY, ATTEMPT, 7, PAYLOAD, new SigningKey(bytes()));
        var signed = WebhookSignature.sign(request, NOW);
        for (var changed : List.of(
                request(UUID.randomUUID(), DELIVERY, ATTEMPT, 7, PAYLOAD, request.signingKey()),
                request(EVENT, UUID.randomUUID(), ATTEMPT, 7, PAYLOAD, request.signingKey()),
                request(EVENT, DELIVERY, UUID.randomUUID(), 7, PAYLOAD, request.signingKey()),
                request(EVENT, DELIVERY, ATTEMPT, 8, PAYLOAD, request.signingKey()),
                request(EVENT, DELIVERY, ATTEMPT, 7, "{}".getBytes(StandardCharsets.UTF_8), request.signingKey()),
                request(EVENT, DELIVERY, ATTEMPT, 7, PAYLOAD, new SigningKey(new byte[32])))) {
            assertThat(WebhookSignature.verify(changed, signed.timestamp(), signed.signature(), NOW)).isFalse();
        }
        for (String timestamp : List.of("01700000000", "+1700000000", "-1", "9223372036854775808", "1700000001")) {
            assertThat(WebhookSignature.verify(request, timestamp, signed.signature(), NOW)).isFalse();
        }
        for (String signature : List.of("", "v2=" + signed.signature().substring(3), signed.signature().toUpperCase(), "v1=00")) {
            assertThat(WebhookSignature.verify(request, signed.timestamp(), signature, NOW)).isFalse();
        }
    }

    @Test
    void invalidMasterConfigurationUsesFixedErrorsWithoutRetainingRejectedValue() {
        for (String invalid : List.of("sensitive-invalid-base64", Base64.getEncoder().encodeToString(new byte[31]),
                Base64.getEncoder().encodeToString(new byte[33]))) {
            assertThatThrownBy(() -> new SigningKeyService(new JdbcTemplate(), new MockEnvironment()
                    .withProperty("relay.signing.master-key", invalid)))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Configure a valid relay signing master key").hasNoCause();
        }
        assertThatThrownBy(() -> new SigningKeyService(new JdbcTemplate(), new MockEnvironment()))
                .hasMessage("Configure a valid relay signing master key").hasNoCause();
    }

    private static DeliveryRequest request(UUID event, UUID delivery, UUID attempt, long generation, byte[] payload, SigningKey key) {
        return new DeliveryRequest(URI.create("https://receiver.example.test/webhook"), payload, event, delivery, attempt, generation, key);
    }

    private static byte[] bytes() {
        byte[] key = new byte[32];
        for (int index = 0; index < key.length; index++) key[index] = (byte) index;
        return key;
    }
}
