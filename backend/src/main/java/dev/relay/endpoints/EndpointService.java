package dev.relay.endpoints;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import dev.relay.common.Page;
import dev.relay.common.PageRequest;
import dev.relay.delivery.http.LocalReceiverPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EndpointService {
    private static final RowMapper<Endpoint> ENDPOINT_ROW = EndpointService::mapEndpoint;

    private final JdbcTemplate jdbc;
    private final LocalReceiverPolicy localReceiver;

    public EndpointService(JdbcTemplate jdbc, LocalReceiverPolicy localReceiver) {
        this.jdbc = jdbc;
        this.localReceiver = localReceiver;
    }

    /** Validates and stores a URL without resolving its host or making an outbound request. */
    @Transactional
    public Endpoint create(UUID ownerAccountId, UUID workspaceId, String url) {
        List<Endpoint> created = jdbc.query("""
                INSERT INTO endpoints (id, workspace_id, url)
                SELECT ?, w.id, ? FROM workspaces w
                WHERE w.id = ? AND w.owner_account_id = ?
                RETURNING id, url, paused, created_at, updated_at
                """, ENDPOINT_ROW, UUID.randomUUID(), validatedUrl(url), workspaceId, ownerAccountId);
        if (created.isEmpty()) {
            throw workspaceNotFound();
        }
        return created.getFirst();
    }

    @Transactional(readOnly = true)
    public Page<Endpoint> listOwned(UUID ownerAccountId, UUID workspaceId, PageRequest page) {
        Boolean owned = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM workspaces WHERE id = ? AND owner_account_id = ?)
                """, Boolean.class, workspaceId, ownerAccountId);
        if (!Boolean.TRUE.equals(owned)) {
            throw workspaceNotFound();
        }
        List<Endpoint> endpoints = jdbc.query("""
                SELECT e.id, e.url, e.paused, e.created_at, e.updated_at
                FROM endpoints e JOIN workspaces w ON w.id = e.workspace_id
                WHERE w.id = ? AND w.owner_account_id = ?
                ORDER BY e.created_at DESC, e.id DESC
                LIMIT ? OFFSET ?
                """, ENDPOINT_ROW, workspaceId, ownerAccountId, page.limit() + 1, page.offset());
        return Page.from(endpoints, page);
    }

    @Transactional(readOnly = true)
    public Endpoint getOwned(UUID ownerAccountId, UUID workspaceId, UUID endpointId) {
        return singleEndpoint(jdbc.query("""
                SELECT e.id, e.url, e.paused, e.created_at, e.updated_at
                FROM endpoints e JOIN workspaces w ON w.id = e.workspace_id
                WHERE w.id = ? AND w.owner_account_id = ? AND e.id = ?
                """, ENDPOINT_ROW, workspaceId, ownerAccountId, endpointId));
    }

    /** PostgreSQL's UPDATE row lock serializes this change with ingestion's FOR SHARE lock. */
    @Transactional
    public Endpoint setPaused(UUID ownerAccountId, UUID workspaceId, UUID endpointId, boolean paused) {
        return singleEndpoint(jdbc.query("""
                UPDATE endpoints e SET paused = ?, updated_at = CURRENT_TIMESTAMP
                FROM workspaces w
                WHERE e.workspace_id = w.id AND w.id = ? AND w.owner_account_id = ? AND e.id = ?
                RETURNING e.id, e.url, e.paused, e.created_at, e.updated_at
                """, ENDPOINT_ROW, paused, workspaceId, ownerAccountId, endpointId));
    }

    /**
     * Registration checks syntax. Transport validates and pins DNS at connection time and refuses redirects.
     */
    private String validatedUrl(String input) {
        if (input == null || input.isBlank() || input.length() > 2048) {
            throw invalidUrl();
        }
        String url = input.strip();
        final URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException invalid) {
            throw invalidUrl();
        }
        String host = parsed.getHost();
        if (!parsed.isAbsolute() || parsed.isOpaque()
                || (!"https".equalsIgnoreCase(parsed.getScheme()) && !localReceiver.allows(parsed))
                || host == null || host.isBlank() || host.chars().anyMatch(character -> character > 127)
                || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null
                || parsed.getPort() == 0 || parsed.getPort() > 65535) {
            throw invalidUrl();
        }
        return url;
    }

    private static Endpoint singleEndpoint(List<Endpoint> endpoints) {
        if (endpoints.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Endpoint not found");
        }
        return endpoints.getFirst();
    }

    private static ResponseStatusException workspaceNotFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
    }

    private static ResponseStatusException invalidUrl() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid endpoint URL");
    }

    private static Endpoint mapEndpoint(ResultSet result, int rowNumber) throws SQLException {
        return new Endpoint(result.getObject("id", UUID.class), result.getString("url"),
                result.getBoolean("paused"), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant());
    }
}
