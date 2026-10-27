--liquibase formatted sql

--changeset lokesh:001-core
CREATE TABLE accounts (
    id             UUID PRIMARY KEY,
    owner_subject  TEXT        NOT NULL,
    currency       VARCHAR(3)  NOT NULL,
    balance_minor  BIGINT      NOT NULL DEFAULT 0,
    allow_negative BOOLEAN     NOT NULL DEFAULT FALSE,
    system_key     TEXT UNIQUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Safety net: even a bug in the service layer cannot overdraw a customer account.
    CONSTRAINT accounts_no_overdraft CHECK (allow_negative OR balance_minor >= 0)
);
CREATE INDEX accounts_owner_idx ON accounts (owner_subject);

CREATE TABLE ledger_transactions (
    id         UUID PRIMARY KEY,
    type       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per leg. Invariant: the legs of a transaction sum to zero.
CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id UUID        NOT NULL REFERENCES ledger_transactions (id),
    account_id     UUID        NOT NULL REFERENCES accounts (id),
    delta_minor    BIGINT      NOT NULL CHECK (delta_minor <> 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id, id DESC);
CREATE INDEX ledger_entries_tx_idx ON ledger_entries (transaction_id);

CREATE TABLE idempotency_keys (
    principal     TEXT        NOT NULL,
    idem_key      TEXT        NOT NULL,
    request_hash  TEXT        NOT NULL,
    transaction_id UUID,
    response_body TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (principal, idem_key)
);

CREATE TABLE outbox_events (
    id           UUID PRIMARY KEY,
    aggregate_id TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished_idx ON outbox_events (created_at) WHERE published_at IS NULL;
