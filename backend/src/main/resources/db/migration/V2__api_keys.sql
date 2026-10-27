CREATE TABLE api_keys (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces (id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    prefix VARCHAR(36) NOT NULL UNIQUE,
    key_hash BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT api_keys_name_nonempty CHECK (length(btrim(name)) > 0),
    CONSTRAINT api_keys_hash_sha256 CHECK (octet_length(key_hash) = 32),
    CONSTRAINT api_keys_prefix_matches_id CHECK (prefix = 'rly_' || replace(id::text, '-', ''))
);

CREATE INDEX api_keys_workspace_id_idx ON api_keys (workspace_id, created_at DESC, id DESC);
