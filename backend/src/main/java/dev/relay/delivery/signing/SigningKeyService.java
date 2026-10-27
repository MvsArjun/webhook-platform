package dev.relay.delivery.signing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SigningKeyService {
    private static final Logger log = LoggerFactory.getLogger(SigningKeyService.class);
    private static final byte[] DOMAIN = "relay.signing.v1\0".getBytes(StandardCharsets.US_ASCII);
    private final JdbcTemplate jdbc;
    private final SecretKeySpec master;
    private final SecureRandom random = new SecureRandom();

    public SigningKeyService(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        byte[] decoded = null;
        try {
            String configured = environment.getProperty("relay.signing.master-key");
            if (configured == null) throw new IllegalArgumentException();
            decoded = Base64.getDecoder().decode(configured);
            if (decoded.length != 32) throw new IllegalArgumentException();
            master = new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException invalid) {
            // No configuration binding or exception cause may echo the externally supplied secret.
            log.atError().addKeyValue("event_type", "signing_configuration_invalid")
                    .log("Configure a valid relay signing master key");
            throw new IllegalStateException("Configure a valid relay signing master key");
        } finally {
            if (decoded != null) Arrays.fill(decoded, (byte) 0);
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SigningKey getOrCreate(UUID workspaceId, UUID endpointId) {
        var existing = stored(workspaceId, endpointId);
        if (!existing.isEmpty()) return decrypt(existing.getFirst(), workspaceId, endpointId);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        try {
            byte[] encrypted = encrypt(secret, workspaceId, endpointId);
            jdbc.update("""
                    INSERT INTO endpoint_signing_keys (workspace_id, endpoint_id, encrypted_key)
                    SELECT e.workspace_id, e.id, ? FROM endpoints e
                    WHERE e.workspace_id = ? AND e.id = ?
                    ON CONFLICT (workspace_id, endpoint_id) DO NOTHING
                    """, encrypted, workspaceId, endpointId);
            // A concurrent insert waits for the winner; this separate READ COMMITTED read sees its key.
            var winner = stored(workspaceId, endpointId);
            if (winner.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found");
            return decrypt(winner.getFirst(), workspaceId, endpointId);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private java.util.List<byte[]> stored(UUID workspaceId, UUID endpointId) {
        return jdbc.query("SELECT encrypted_key FROM endpoint_signing_keys WHERE workspace_id = ? AND endpoint_id = ?",
                (row, index) -> row.getBytes("encrypted_key"), workspaceId, endpointId);
    }

    private byte[] encrypt(byte[] value, UUID workspaceId, UUID endpointId) {
        byte[] iv = new byte[12];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(workspaceId, endpointId));
            byte[] ciphertext = cipher.doFinal(value);
            return ByteBuffer.allocate(iv.length + ciphertext.length).put(iv).put(ciphertext).array();
        } catch (GeneralSecurityException unavailable) {
            throw unavailable();
        }
    }

    private SigningKey decrypt(byte[] encrypted, UUID workspaceId, UUID endpointId) {
        if (encrypted == null || encrypted.length != 60) throw unavailable();
        byte[] plaintext = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(128, encrypted, 0, 12));
            cipher.updateAAD(aad(workspaceId, endpointId));
            plaintext = cipher.doFinal(encrypted, 12, encrypted.length - 12);
            return new SigningKey(plaintext);
        } catch (GeneralSecurityException invalid) {
            throw unavailable();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static byte[] aad(UUID workspaceId, UUID endpointId) {
        return ByteBuffer.allocate(DOMAIN.length + 32).put(DOMAIN)
                .putLong(workspaceId.getMostSignificantBits()).putLong(workspaceId.getLeastSignificantBits())
                .putLong(endpointId.getMostSignificantBits()).putLong(endpointId.getLeastSignificantBits()).array();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("Endpoint signing key is unavailable");
    }
}
