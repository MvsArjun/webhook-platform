-- Empty foundational tables only. Authentication and ownership behavior arrive in Phase 1.
CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT accounts_email_normalized CHECK (email = lower(btrim(email))),
    CONSTRAINT accounts_email_nonempty CHECK (length(email) > 0),
    CONSTRAINT accounts_password_hash_nonempty CHECK (length(password_hash) > 0)
);

CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES accounts (id),
    name VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT workspaces_name_nonempty CHECK (length(btrim(name)) > 0)
);

CREATE INDEX workspaces_owner_account_id_idx ON workspaces (owner_account_id);

