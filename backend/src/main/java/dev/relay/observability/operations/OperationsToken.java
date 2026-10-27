package dev.relay.observability.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/** Independently scoped operator credential; only its digest is retained by this object. */
public final class OperationsToken {
    private static final Logger log = LoggerFactory.getLogger(OperationsToken.class);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private final byte[] digest;

    private OperationsToken(byte[] digest) { this.digest = digest; }

    /** Manual access avoids secret-bearing property-binding diagnostics on startup failure. */
    public static OperationsToken from(Environment environment) {
        try {
            String enabled = environment.getProperty("relay.operations.enabled", "false");
            if ("false".equalsIgnoreCase(enabled)) return new OperationsToken(null);
            if (!"true".equalsIgnoreCase(enabled)) throw invalidConfiguration();
            String raw = environment.getProperty("relay.operations.token");
            if (!valid(raw)) throw invalidConfiguration();
            return new OperationsToken(hash(raw));
        } catch (RuntimeException invalid) {
            // Placeholder resolution errors can themselves contain the raw configured value.
            log.atError().addKeyValue("event_type", "operations_configuration_invalid")
                    .log("Invalid operations configuration");
            throw invalidConfiguration();
        }
    }

    public boolean authenticate(String raw) {
        return digest != null && valid(raw) && MessageDigest.isEqual(digest, hash(raw));
    }

    private static boolean valid(String raw) {
        if (raw == null || !TOKEN.matcher(raw).matches()) return false;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(raw);
            return bytes.length == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(raw);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static byte[] hash(String raw) {
        try { return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.US_ASCII)); }
        catch (NoSuchAlgorithmException unavailable) { throw invalidConfiguration(); }
    }

    private static IllegalStateException invalidConfiguration() {
        return new IllegalStateException("Invalid operations configuration");
    }

    @Override public String toString() { return "OperationsToken[REDACTED]"; }
}
