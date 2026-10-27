package dev.relay.observability.operations;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class OperationsTokenTest {
    @Test
    void independentCanonicalTokenAuthenticatesWithoutRetainingItInDiagnostics() {
        String raw = token((byte) 17);
        var credential = OperationsToken.from(new MockEnvironment().withProperty("relay.operations.enabled", "true")
                .withProperty("relay.operations.token", raw));
        assertThat(credential.authenticate(raw)).isTrue();
        assertThat(credential.authenticate(token((byte) 18))).isFalse();
        assertThat(credential.toString()).isEqualTo("OperationsToken[REDACTED]").doesNotContain(raw);
    }

    @Test
    void disabledOperationsCannotAuthenticateEvenWhenATokenIsConfigured() {
        assertThat(OperationsToken.from(new MockEnvironment()).authenticate(token((byte) 17))).isFalse();
        var disabled = OperationsToken.from(new MockEnvironment().withProperty("relay.operations.enabled", "false")
                .withProperty("relay.operations.token", token((byte) 17)));
        assertThat(disabled.authenticate(token((byte) 17))).isFalse();
    }

    @Test
    void enabledConfigurationRejectsMissingMalformedAndNonCanonicalTokensWithFixedErrors() {
        assertInvalid(new MockEnvironment().withProperty("relay.operations.enabled", "true"));
        String valid = token((byte) 17);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        String nonCanonical = valid.substring(0, 42) + alphabet.charAt(alphabet.indexOf(valid.charAt(42)) + 1);
        for (String raw : List.of("private-operator-marker", valid + "=", valid.substring(1), " " + valid,
                "a".repeat(10000), nonCanonical)) {
            assertInvalid(new MockEnvironment().withProperty("relay.operations.enabled", "true")
                    .withProperty("relay.operations.token", raw));
        }
        assertInvalid(new MockEnvironment().withProperty("relay.operations.enabled", "private-enabled-marker"));
        assertInvalid(new MockEnvironment().withProperty("relay.operations.enabled", "true")
                .withProperty("relay.operations.token", "${private-unresolved-marker}"));
    }

    @Test
    void malformedRequestValuesUniformlyFailWithoutThrowing() {
        String raw = token((byte) 17);
        var credential = OperationsToken.from(new MockEnvironment().withProperty("relay.operations.enabled", "true")
                .withProperty("relay.operations.token", raw));
        assertThat(credential.authenticate(null)).isFalse();
        for (String candidate : List.of("", raw + "=", raw.substring(1), "a".repeat(10000),
                "rly_" + "a".repeat(32) + "." + raw, raw + "," + raw)) {
            assertThat(credential.authenticate(candidate)).isFalse();
        }
    }

    private static void assertInvalid(MockEnvironment environment) {
        assertThatExceptionOfType(IllegalStateException.class).isThrownBy(() -> OperationsToken.from(environment))
                .withMessage("Invalid operations configuration").withNoCause();
    }

    static String token(byte fill) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, fill);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
