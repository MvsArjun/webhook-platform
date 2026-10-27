ALTER TABLE deliveries DROP CONSTRAINT deliveries_status_allowed;
ALTER TABLE deliveries
    ADD COLUMN replay_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN run_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN max_attempts INTEGER NOT NULL DEFAULT 5,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD CONSTRAINT deliveries_status_allowed
        CHECK (status IN ('PENDING', 'RETRY_WAIT', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED'));

-- Preserve pre-policy history as run zero. Already-used attempts consume the first run's budget.
UPDATE deliveries SET run_attempts = claim_generation::INTEGER,
    max_attempts = GREATEST(5, claim_generation::INTEGER);
UPDATE deliveries SET status = 'FAILED'
    WHERE status = 'PENDING' AND run_attempts >= max_attempts;

ALTER TABLE deliveries
    ADD CONSTRAINT deliveries_retry_budget CHECK (max_attempts >= 1
        AND run_attempts >= 0 AND run_attempts <= max_attempts),
    ADD CONSTRAINT deliveries_replay_nonnegative CHECK (replay_generation >= 0),
    ADD CONSTRAINT deliveries_retry_schedule CHECK (
        (status = 'RETRY_WAIT' AND next_attempt_at IS NOT NULL)
        OR (status <> 'RETRY_WAIT' AND next_attempt_at IS NULL));

ALTER TABLE delivery_attempts
    ADD COLUMN replay_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN run_attempt INTEGER NOT NULL DEFAULT 1;
UPDATE delivery_attempts SET run_attempt = claim_generation::INTEGER;
ALTER TABLE delivery_attempts
    ADD CONSTRAINT attempts_replay_nonnegative CHECK (replay_generation >= 0),
    ADD CONSTRAINT attempts_run_positive CHECK (run_attempt > 0),
    ADD CONSTRAINT attempts_run_unique UNIQUE (delivery_id, replay_generation, run_attempt);

CREATE INDEX deliveries_retry_due_idx ON deliveries (next_attempt_at, id)
    WHERE status = 'RETRY_WAIT';
