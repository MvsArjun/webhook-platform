package dev.relay.workspaces;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import dev.relay.delivery.DeliveryLimits;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WorkspaceService {
    private static final RowMapper<Workspace> WORKSPACE_ROW = WorkspaceService::mapWorkspace;

    private final JdbcTemplate jdbc;
    private final DeliveryLimits limits;

    public WorkspaceService(JdbcTemplate jdbc, DeliveryLimits limits) {
        this.jdbc = jdbc;
        this.limits = limits;
    }

    @Transactional
    public Workspace create(UUID ownerAccountId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO workspaces (id, owner_account_id, name, delivery_concurrency_limit)
                VALUES (?, ?, ?, ?)
                RETURNING id, name, created_at, delivery_concurrency_limit
                """, WORKSPACE_ROW, UUID.randomUUID(), ownerAccountId, normalizedName(name), limits.workspaceConcurrencyLimit());
    }

    @Transactional(readOnly = true)
    public List<Workspace> listOwned(UUID ownerAccountId) {
        return jdbc.query("""
                SELECT id, name, created_at, delivery_concurrency_limit FROM workspaces
                WHERE owner_account_id = ?
                ORDER BY created_at DESC, id DESC
                """, WORKSPACE_ROW, ownerAccountId);
    }

    /** Missing workspaces and workspaces belonging to another account both produce 404. */
    @Transactional(readOnly = true)
    public Workspace getOwned(UUID ownerAccountId, UUID workspaceId) {
        return singleWorkspace(jdbc.query("""
                SELECT id, name, created_at, delivery_concurrency_limit FROM workspaces
                WHERE id = ? AND owner_account_id = ?
                """, WORKSPACE_ROW, workspaceId, ownerAccountId));
    }

    @Transactional
    public Workspace rename(UUID ownerAccountId, UUID workspaceId, String name) {
        return singleWorkspace(jdbc.query("""
                UPDATE workspaces SET name = ?
                WHERE id = ? AND owner_account_id = ?
                RETURNING id, name, created_at, delivery_concurrency_limit
                """, WORKSPACE_ROW, normalizedName(name), workspaceId, ownerAccountId));
    }

    /** Only pass the workspace ID from a successfully authenticated API key principal. */
    @Transactional(readOnly = true)
    public Workspace getForApiKey(UUID workspaceId) {
        return singleWorkspace(jdbc.query("""
                SELECT id, name, created_at, delivery_concurrency_limit FROM workspaces WHERE id = ?
                """, WORKSPACE_ROW, workspaceId));
    }

    private static Workspace singleWorkspace(List<Workspace> workspaces) {
        if (workspaces.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workspace not found");
        }
        return workspaces.getFirst();
    }

    private static String normalizedName(String name) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must contain 1 to 100 characters");
        }
        return name.strip();
    }

    private static Workspace mapWorkspace(ResultSet result, int rowNumber) throws SQLException {
        return new Workspace(result.getObject("id", UUID.class), result.getString("name"),
                result.getTimestamp("created_at").toInstant(), result.getInt("delivery_concurrency_limit"));
    }
}
