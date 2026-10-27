ALTER TABLE deliveries DROP CONSTRAINT deliveries_status_allowed;
ALTER TABLE deliveries
    ADD COLUMN claim_token UUID,
    ADD COLUMN claim_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD CONSTRAINT deliveries_status_allowed
        CHECK (status IN ('PENDING', 'IN_FLIGHT', 'SUCCEEDED', 'FAILED')),
    ADD CONSTRAINT deliveries_generation_nonnegative CHECK (claim_generation >= 0),
    ADD CONSTRAINT deliveries_claim_consistent CHECK (
        (status = 'IN_FLIGHT' AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL
            AND claim_generation > 0)
        OR (status <> 'IN_FLIGHT' AND claim_token IS NULL AND lease_expires_at IS NULL)
    );

CREATE INDEX deliveries_expired_lease_idx ON deliveries (lease_expires_at, id)
    WHERE status = 'IN_FLIGHT';

CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    delivery_id UUID NOT NULL,
    claim_generation BIGINT NOT NULL,
    claim_token UUID NOT NULL UNIQUE,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    outcome VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    CONSTRAINT delivery_attempts_workspace_delivery_fk FOREIGN KEY (workspace_id, delivery_id)
        REFERENCES deliveries (workspace_id, id),
    CONSTRAINT delivery_attempts_generation_positive CHECK (claim_generation > 0),
    CONSTRAINT delivery_attempts_delivery_generation_unique UNIQUE (delivery_id, claim_generation),
    CONSTRAINT delivery_attempts_outcome_allowed
        CHECK (outcome IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT delivery_attempts_finish_consistent CHECK (
        (outcome = 'IN_PROGRESS' AND finished_at IS NULL)
        OR (outcome <> 'IN_PROGRESS' AND finished_at IS NOT NULL)
    )
);

CREATE INDEX delivery_attempts_workspace_delivery_idx
    ON delivery_attempts (workspace_id, delivery_id, claim_generation);
