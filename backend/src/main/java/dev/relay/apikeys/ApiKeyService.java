package dev.relay.apikeys;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ApiKeyService {
    private static final int RAW_KEY_LENGTH = 80;
    private static final Pattern RAW_KEY_FORMAT = Pattern.compile("rly_([0-9a-f]{32})\\.[A-Za-z0-9_-]{43}");
    private static final RowMapper<ApiKeyMetadata> METADATA_ROW = ApiKeyService::mapMetadata;

    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public ApiKeyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ownership is part of the INSERT predicate, so unauthorized callers cannot create keys. */
    @Transactional
    public CreatedApiKey create(UUID ownerAccountId, UUID workspaceId, String name) {
        String normalizedName = normalizedName(name);
        UUID id = UUID.randomUUID();
        String prefix = "rly_" + id.toString().replace("-", "");
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String raw = prefix + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        List<ApiKeyMetadata> created = jdbc.query("""
                INSERT INTO api_keys (id, workspace_id, name, prefix, key_hash)
                SELECT ?, w.id, ?, ?, ? FROM workspaces w
                WHERE w.id = ? AND w.owner_account_id = ?
                RETURNING id, name, prefix, created_at, revoked_at
                """, METADATA_ROW, id, normalizedName, prefix, sha256(raw), workspaceId, ownerAccountId);
        if (created.isEmpty()) {
            throw workspaceNotFound();
        }
        ApiKeyMetadata metadata = created.getFirst();
        return new CreatedApiKey(metadata.id(), metadata.name(), metadata.prefix(),
                metadata.createdAt(), metadata.revokedAt(), raw);
    }

    @Transactional(readOnly = true)
    public List<ApiKeyMetadata> listOwned(UUID ownerAccountId, UUID workspaceId) {
        Boolean owned = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM workspaces WHERE id = ? AND owner_account_id = ?)
                """, Boolean.class, workspaceId, ownerAccountId);
        if (!Boolean.TRUE.equals(owned)) {
            throw workspaceNotFound();
        }
        return jdbc.query("""
                SELECT k.id, k.name, k.prefix, k.created_at, k.revoked_at
                FROM api_keys k JOIN workspaces w ON w.id = k.workspace_id
                WHERE w.id = ? AND w.owner_account_id = ?
                ORDER BY k.created_at DESC, k.id DESC
                """, METADATA_ROW, workspaceId, ownerAccountId);
    }

    /** Revocation is idempotent, and both workspace and owner are checked in the mutation. */
    @Transactional
    public void revoke(UUID ownerAccountId, UUID workspaceId, UUID keyId) {
        int changed = jdbc.update("""
                UPDATE api_keys k SET revoked_at = COALESCE(k.revoked_at, CURRENT_TIMESTAMP)
                FROM workspaces w
                WHERE k.workspace_id = w.id AND w.id = ? AND w.owner_account_id = ? AND k.id = ?
                """, workspaceId, ownerAccountId, keyId);
        if (changed == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "API key not found");
        }
    }

    /** Re-reads active credentials for every request; no cache can postpone revocation. */
    @Transactional(readOnly = true)
    public Optional<ApiKeyPrincipal> authenticate(String raw) {
        if (raw == null || raw.length() != RAW_KEY_LENGTH) {
            return Optional.empty();
        }
        var match = RAW_KEY_FORMAT.matcher(raw);
        if (!match.matches()) {
            return Optional.empty();
        }
        String hexId = match.group(1);
        UUID id = UUID.fromString(hexId.substring(0, 8) + "-" + hexId.substring(8, 12) + "-"
                + hexId.substring(12, 16) + "-" + hexId.substring(16, 20) + "-" + hexId.substring(20));
        List<StoredCredential> credentials = jdbc.query("""
                SELECT id, workspace_id, key_hash FROM api_keys WHERE id = ? AND revoked_at IS NULL
                """, (result, rowNumber) -> new StoredCredential(result.getObject("id", UUID.class),
                result.getObject("workspace_id", UUID.class), result.getBytes("key_hash")), id);
        if (credentials.isEmpty()) {
            return Optional.empty();
        }
        StoredCredential credential = credentials.getFirst();
        return MessageDigest.isEqual(credential.hash(), sha256(raw))
                ? Optional.of(new ApiKeyPrincipal(credential.id(), credential.workspaceId()))
                : Optional.empty();
    }

    private static byte[] sha256(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String normalizedName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must contain 1 to 100 characters");
        }
        return name.strip();
    }

    private static ResponseStatusException workspaceNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
    }

    private static ApiKeyMetadata mapMetadata(ResultSet result, int rowNumber) throws SQLException {
        Timestamp revokedAt = result.getTimestamp("revoked_at");
        return new ApiKeyMetadata(result.getObject("id", UUID.class), result.getString("name"),
                result.getString("prefix"), result.getTimestamp("created_at").toInstant(),
                revokedAt == null ? null : revokedAt.toInstant());
    }

    private record StoredCredential(UUID id, UUID workspaceId, byte[] hash) {
        @Override
        public String toString() {
            return "StoredCredential[id=" + id + "]";
        }
    }
}
