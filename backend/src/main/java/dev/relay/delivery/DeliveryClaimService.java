package dev.relay.delivery;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.relay.delivery.retry.RetryPolicy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short database-only claim, recovery and completion transactions. Callers must never perform
 * network work inside these methods or an enclosing transaction.
 */
@Service
public class DeliveryClaimService {
    private static final Duration MINIMUM_LEASE = Duration.ofMillis(100);
    private static final Duration MAXIMUM_LEASE = Duration.ofMillis(300000);

    private final JdbcTemplate jdbc;
    private final RetryPolicy retry;

    public DeliveryClaimService(JdbcTemplate jdbc, RetryPolicy retry) {
        this.jdbc = jdbc;
        this.retry = retry;
    }

    /** Claims at most the caller's available capacity, skipping locked jobs and pause updates. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<ClaimedDelivery> claim(int limit, Duration lease) {
        validateLimit(limit);
        long leaseMillis = validatedLeaseMillis(lease);
        List<DeliveryIdentity> candidates = jdbc.query("""
                SELECT d.id, d.event_id, d.workspace_id
                FROM deliveries d
                JOIN events e ON e.workspace_id = d.workspace_id AND e.id = d.event_id
                JOIN endpoints ep ON ep.workspace_id = e.workspace_id AND ep.id = e.endpoint_id
                JOIN workspaces w ON w.id = d.workspace_id
                WHERE (d.status = 'PENDING' OR (d.status = 'RETRY_WAIT' AND d.next_attempt_at <= ?))
                    AND d.run_attempts < d.max_attempts AND ep.paused = FALSE
                    AND (SELECT count(*) FROM deliveries active
                        WHERE active.workspace_id = w.id AND active.status = 'IN_FLIGHT') < w.delivery_concurrency_limit
                ORDER BY d.created_at, d.id
                LIMIT ?
                FOR UPDATE OF d SKIP LOCKED
                FOR SHARE OF ep SKIP LOCKED
                """, (row, index) -> new DeliveryIdentity(row.getObject("id", UUID.class),
                row.getObject("event_id", UUID.class), row.getObject("workspace_id", UUID.class)),
                Timestamp.from(retry.now()), limit);

        List<ClaimedDelivery> claims = new ArrayList<>(candidates.size());
        for (DeliveryIdentity candidate : candidates) {
            // All claimers serialize each workspace's count+increment. NO KEY UPDATE stays
            // compatible with ingestion FK key-share locks; SKIP LOCKED keeps claims bounded.
            var workspaceLimit = jdbc.query("""
                    SELECT delivery_concurrency_limit FROM workspaces WHERE id = ?
                    FOR NO KEY UPDATE SKIP LOCKED
                    """, (row, index) -> row.getInt("delivery_concurrency_limit"), candidate.workspaceId());
            if (workspaceLimit.isEmpty()) continue;
            Integer active = jdbc.queryForObject("""
                    SELECT count(*) FROM deliveries WHERE workspace_id = ? AND status = 'IN_FLIGHT'
                    """, Integer.class, candidate.workspaceId());
            if (active == null || active >= workspaceLimit.getFirst()) continue;
            UUID token = UUID.randomUUID();
            UUID attemptId = UUID.randomUUID();
            List<LeaseState> updated = jdbc.query("""
                    UPDATE deliveries
                    SET status = 'IN_FLIGHT', claim_token = ?, claim_generation = claim_generation + 1,
                        run_attempts = run_attempts + 1, next_attempt_at = NULL,
                        lease_expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond')
                    WHERE id = ? AND event_id = ? AND workspace_id = ? AND status IN ('PENDING', 'RETRY_WAIT')
                        AND run_attempts < max_attempts
                    RETURNING claim_generation, lease_expires_at, replay_generation, run_attempts
                    """, (row, index) -> new LeaseState(row.getLong("claim_generation"),
                    row.getTimestamp("lease_expires_at").toInstant(), row.getLong("replay_generation"),
                    row.getInt("run_attempts")), token, leaseMillis,
                    candidate.deliveryId(), candidate.eventId(), candidate.workspaceId());
            if (updated.size() != 1) {
                throw inconsistentState();
            }
            LeaseState state = updated.getFirst();
            requireOne(jdbc.update("""
                    INSERT INTO delivery_attempts (id, workspace_id, delivery_id, claim_generation, claim_token,
                        replay_generation, run_attempt)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, attemptId, candidate.workspaceId(), candidate.deliveryId(), state.generation(), token,
                    state.replayGeneration(), state.runAttempt()));
            claims.add(new ClaimedDelivery(candidate.deliveryId(), candidate.eventId(), candidate.workspaceId(),
                    attemptId, token, state.generation(), state.expiresAt()));
        }
        // The transaction interceptor commits the delivery and ledger changes before returning.
        return List.copyOf(claims);
    }

    /** Expired work is eligible again even if its endpoint has since been paused. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public int recoverExpired(int limit) {
        validateLimit(limit);
        List<ExpiredClaim> expired = jdbc.query("""
                SELECT d.id, d.event_id, d.workspace_id, d.claim_token, d.claim_generation,
                    d.run_attempts, d.max_attempts
                FROM deliveries d
                WHERE d.status = 'IN_FLIGHT' AND d.lease_expires_at <= clock_timestamp()
                ORDER BY d.lease_expires_at, d.id
                LIMIT ?
                FOR UPDATE OF d SKIP LOCKED
                """, (row, index) -> new ExpiredClaim(row.getObject("id", UUID.class),
                row.getObject("event_id", UUID.class), row.getObject("workspace_id", UUID.class),
                row.getObject("claim_token", UUID.class), row.getLong("claim_generation"),
                row.getInt("run_attempts"), row.getInt("max_attempts")), limit);

        for (ExpiredClaim claim : expired) {
            Instant next = claim.attemptsUsed() < claim.maxAttempts() ? retry.nextAttemptAt(claim.attemptsUsed()) : null;
            requireOne(jdbc.update("""
                    UPDATE delivery_attempts SET outcome = 'UNKNOWN', finished_at = clock_timestamp()
                    WHERE workspace_id = ? AND delivery_id = ? AND claim_generation = ?
                        AND claim_token = ? AND outcome = 'IN_PROGRESS'
                    """, claim.workspaceId(), claim.deliveryId(), claim.generation(), claim.token()));
            requireOne(jdbc.update("""
                    UPDATE deliveries SET status = ?, next_attempt_at = ?, claim_token = NULL, lease_expires_at = NULL
                    WHERE id = ? AND event_id = ? AND workspace_id = ? AND status = 'IN_FLIGHT'
                        AND claim_token = ? AND claim_generation = ? AND lease_expires_at <= clock_timestamp()
                    """, next == null ? "FAILED" : "RETRY_WAIT", timestamp(next),
                    claim.deliveryId(), claim.eventId(), claim.workspaceId(), claim.token(), claim.generation()));
        }
        return expired.size();
    }

    /** Returns false without touching history when the claim is stale, expired or already completed. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean complete(ClaimedDelivery claim, CompletionOutcome outcome) {
        return completeStored(claim, outcome, null, null, null, false, null, false);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public boolean complete(ClaimedDelivery claim, DeliveryResult result) {
        if (result == null) {
            throw new IllegalArgumentException("A delivery result is required");
        }
        return completeStored(claim, result.outcome(), result.httpStatus(), result.failureCode(),
                result.durationMillis(), retry.retryable(result), result.responsePreview(), result.responseTruncated());
    }

    private boolean completeStored(ClaimedDelivery claim, CompletionOutcome outcome, Integer httpStatus,
                                   String failureCode, Long durationMillis, boolean retryable,
                                   String responsePreview, boolean responseTruncated) {
        if (claim == null || outcome == null) {
            throw new IllegalArgumentException("A claim and completion outcome are required");
        }
        // Acquire the row before testing the clock. A lock wait may outlast an otherwise valid lease,
        // even if the lock holder never changes the row and PostgreSQL need not recheck its predicate.
        List<RunState> locked = jdbc.query("""
                SELECT run_attempts, max_attempts, replay_generation FROM deliveries
                WHERE id = ? AND event_id = ? AND workspace_id = ? AND status = 'IN_FLIGHT'
                    AND claim_token = ? AND claim_generation = ?
                FOR UPDATE
                """, (row, index) -> new RunState(row.getInt("run_attempts"), row.getInt("max_attempts"),
                row.getLong("replay_generation")), claim.deliveryId(), claim.eventId(),
                claim.workspaceId(), claim.token(), claim.generation());
        if (locked.isEmpty()) {
            return false;
        }
        RunState run = locked.getFirst();
        Instant next = retryable && run.attemptsUsed() < run.maxAttempts()
                ? retry.nextAttemptAt(run.attemptsUsed()) : null;
        int completed = jdbc.update("""
                UPDATE deliveries SET status = ?, next_attempt_at = ?, claim_token = NULL, lease_expires_at = NULL
                WHERE id = ? AND event_id = ? AND workspace_id = ? AND status = 'IN_FLIGHT'
                    AND claim_token = ? AND claim_generation = ? AND lease_expires_at > clock_timestamp()
                """, next == null ? outcome.name() : "RETRY_WAIT", timestamp(next),
                claim.deliveryId(), claim.eventId(), claim.workspaceId(),
                claim.token(), claim.generation());
        if (completed == 0) {
            return false;
        }
        requireOne(completed);
        requireOne(jdbc.update("""
                UPDATE delivery_attempts SET outcome = ?, finished_at = clock_timestamp(),
                    http_status = ?, failure_code = ?, duration_ms = ?, response_preview = ?, response_truncated = ?
                WHERE id = ? AND workspace_id = ? AND delivery_id = ? AND claim_generation = ?
                    AND claim_token = ? AND outcome = 'IN_PROGRESS' AND replay_generation = ? AND run_attempt = ?
                """, outcome.name(), httpStatus, failureCode, durationMillis, responsePreview, responseTruncated,
                claim.attemptId(), claim.workspaceId(), claim.deliveryId(),
                claim.generation(), claim.token(), run.replayGeneration(), run.attemptsUsed()));
        return true;
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Claim and recovery limits must be between 1 and 100");
        }
    }

    private static long validatedLeaseMillis(Duration lease) {
        if (lease == null || lease.compareTo(MINIMUM_LEASE) < 0 || lease.compareTo(MAXIMUM_LEASE) > 0) {
            throw new IllegalArgumentException("Lease duration must be between 100 and 300000 milliseconds");
        }
        return lease.toMillis();
    }

    private static void requireOne(int changed) {
        if (changed != 1) {
            throw inconsistentState();
        }
    }

    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static IllegalStateException inconsistentState() {
        // Fixed internal text: no payload, URL, claim token or database row details.
        return new IllegalStateException("Delivery claim and attempt ledger are inconsistent");
    }

    private record DeliveryIdentity(UUID deliveryId, UUID eventId, UUID workspaceId) {}

    private record LeaseState(long generation, Instant expiresAt, long replayGeneration, int runAttempt) {}

    private record RunState(int attemptsUsed, int maxAttempts, long replayGeneration) {}

    private record ExpiredClaim(UUID deliveryId, UUID eventId, UUID workspaceId, UUID token, long generation,
                                int attemptsUsed, int maxAttempts) {
        @Override
        public String toString() {
            return "ExpiredClaim[deliveryId=" + deliveryId + ", token=REDACTED]";
        }
    }
}
