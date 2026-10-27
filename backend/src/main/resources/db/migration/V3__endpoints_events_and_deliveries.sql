CREATE TABLE endpoints (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id),
    url TEXT NOT NULL,
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT endpoints_url_length CHECK (length(btrim(url)) BETWEEN 1 AND 2048),
    CONSTRAINT endpoints_workspace_id_unique UNIQUE (workspace_id, id)
);

CREATE INDEX endpoints_workspace_created_idx ON endpoints (workspace_id, created_at DESC, id DESC);

CREATE TABLE events (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id),
    endpoint_id UUID NOT NULL,
    payload BYTEA NOT NULL,
    request_digest BYTEA NOT NULL,
    idempotency_key VARCHAR(128),
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT events_workspace_endpoint_fk FOREIGN KEY (workspace_id, endpoint_id)
        REFERENCES endpoints (workspace_id, id),
    CONSTRAINT events_workspace_id_unique UNIQUE (workspace_id, id),
    CONSTRAINT events_workspace_idempotency_unique UNIQUE (workspace_id, idempotency_key),
    CONSTRAINT events_payload_size CHECK (octet_length(payload) BETWEEN 1 AND 262144),
    CONSTRAINT events_digest_sha256 CHECK (octet_length(request_digest) = 32),
    CONSTRAINT events_idempotency_format CHECK
        (idempotency_key IS NULL OR idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$')
);

CREATE INDEX events_workspace_accepted_idx ON events (workspace_id, accepted_at DESC, id DESC);
CREATE INDEX events_workspace_endpoint_idx ON events (workspace_id, endpoint_id);

CREATE TABLE deliveries (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT deliveries_workspace_event_fk FOREIGN KEY (workspace_id, event_id)
        REFERENCES events (workspace_id, id),
    CONSTRAINT deliveries_workspace_id_unique UNIQUE (workspace_id, id),
    -- Phase 3 extends this constraint when worker state transitions are implemented.
    CONSTRAINT deliveries_status_allowed CHECK (status = 'PENDING')
);

CREATE INDEX deliveries_pending_created_idx ON deliveries (created_at, id) WHERE status = 'PENDING';
