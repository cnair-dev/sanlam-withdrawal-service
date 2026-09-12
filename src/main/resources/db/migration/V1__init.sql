-- ---------------------------------------------------------------------------
-- Withdrawal service schema.
--
-- Design note: all four writes of a withdrawal (balance, ledger, idempotency,
-- outbox) happen in ONE database transaction. A relational transaction is
-- atomic across multiple tables - that single property is what solves the
-- dual-write problem, idempotency safety and the audit trail simultaneously.
-- ---------------------------------------------------------------------------

CREATE TABLE accounts (
    id              BIGINT        PRIMARY KEY,
    balance         NUMERIC(19,2) NOT NULL,
    currency        CHAR(3)       NOT NULL DEFAULT 'ZAR',
    status          TEXT          NOT NULL DEFAULT 'ACTIVE',
    is_system       BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT accounts_status_chk CHECK (status IN ('ACTIVE','FROZEN','DORMANT','CLOSED'))
);

-- Insert-only double-entry ledger. NEVER updated or deleted.
-- transaction_id groups the legs of one movement: without it you can only verify
-- that the ledger balances GLOBALLY, not that any individual transaction did.
CREATE TABLE ledger_entry (
    id             BIGSERIAL     PRIMARY KEY,
    transaction_id UUID          NOT NULL,
    account_id     BIGINT        NOT NULL REFERENCES accounts(id),
    direction      TEXT          NOT NULL,
    amount         NUMERIC(19,2) NOT NULL,
    currency       CHAR(3)       NOT NULL,
    correlation_id TEXT          NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ledger_direction_chk CHECK (direction IN ('DEBIT','CREDIT')),
    -- A movement of less than one cent cannot be represented, so it is not a
    -- movement. Note this cannot substitute for validating scale at the API
    -- boundary: NUMERIC(19,2) coerces the value before any CHECK on it runs, so
    -- a CHECK on scale() is a tautology and 0.005 would arrive here as 0.01.
    CONSTRAINT ledger_amount_chk    CHECK (amount >= 0.01)
);
CREATE INDEX idx_ledger_txn     ON ledger_entry(transaction_id);
CREATE INDEX idx_ledger_account ON ledger_entry(account_id, created_at);

-- Transactional outbox. published_at/status are the state machine.
CREATE TABLE outbox_event (
    id              BIGSERIAL     PRIMARY KEY,
    aggregate_id    BIGINT        NOT NULL,
    event_type      TEXT          NOT NULL,
    payload         JSONB         NOT NULL,
    event_version   INT           NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ   NULL,
    attempt_count   INT           NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_error      TEXT          NULL,
    status          TEXT          NOT NULL DEFAULT 'PENDING',
    CONSTRAINT outbox_status_chk CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
);
-- Partial index: the relay's claim query only ever scans PENDING rows, so the
-- index stays small no matter how large the published history grows.
CREATE INDEX idx_outbox_claim ON outbox_event(next_attempt_at, id) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_purge ON outbox_event(published_at) WHERE status = 'PUBLISHED';

-- Idempotency keys are scoped PER CLIENT so one caller cannot squat or collide
-- with another caller's key. request_hash binds the key to the request that
-- created it: replaying a key with different parameters is a client error (422),
-- not a silent no-op.
CREATE TABLE idempotency_key (
    client_id       TEXT          NOT NULL,
    idempotency_key TEXT          NOT NULL,
    request_hash    TEXT          NOT NULL,
    account_id      BIGINT        NULL,
    response_status INT           NULL,
    response_body   JSONB         NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (client_id, idempotency_key)
);
CREATE INDEX idx_idem_expiry ON idempotency_key(expires_at);

-- System settlement account. Ledger-only: its accounts.balance is deliberately
-- NOT maintained, because every withdrawal in the system credits this one row.
-- Maintaining a cached balance here would make it the hottest row in the
-- database. Its position is derived by summing ledger_entry.
INSERT INTO accounts(id, balance, currency, status, is_system)
VALUES (9000, 0.00, 'ZAR', 'ACTIVE', TRUE);
