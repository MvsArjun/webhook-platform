ALTER TABLE workspaces ADD COLUMN delivery_concurrency_limit INTEGER NOT NULL DEFAULT 4,
    ADD CONSTRAINT workspace_delivery_limit CHECK (delivery_concurrency_limit BETWEEN 1 AND 32);

CREATE TABLE endpoint_signing_keys (
    workspace_id UUID NOT NULL,
    endpoint_id UUID NOT NULL,
    encrypted_key BYTEA NOT NULL,
    PRIMARY KEY (workspace_id, endpoint_id),
    CONSTRAINT signing_key_endpoint_fk FOREIGN KEY (workspace_id, endpoint_id)
        REFERENCES endpoints (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT signing_key_envelope_length CHECK (octet_length(encrypted_key) = 60)
);

ALTER TABLE delivery_attempts
    ADD COLUMN response_preview TEXT,
    ADD COLUMN response_truncated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT attempt_preview_bounded CHECK (
        response_preview IS NULL OR (length(response_preview) <= 16384 AND octet_length(response_preview) <= 49152)),
    ADD CONSTRAINT attempt_preview_consistent CHECK (
        (http_status IS NOT NULL AND outcome IN ('SUCCEEDED', 'FAILED'))
        OR (response_preview IS NULL AND response_truncated = FALSE));

CREATE INDEX deliveries_workspace_inflight_idx ON deliveries (workspace_id, id) WHERE status = 'IN_FLIGHT';
