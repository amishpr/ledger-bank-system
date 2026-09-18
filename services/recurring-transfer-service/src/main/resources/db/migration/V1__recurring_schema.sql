-- A local, read-only copy of the ledger's accounts, kept up to date from
-- ledger.accounts.v1. It lets this service list and validate schedules
-- without calling the ledger, and keeps working while the ledger is down.
CREATE TABLE account_replica (
    id             UUID         PRIMARY KEY,
    name           VARCHAR(120) NOT NULL,
    type           VARCHAR(16)  NOT NULL,
    currency       VARCHAR(3)   NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    replicated_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX account_replica_name_idx ON account_replica (name);

-- anchor_at is the first occurrence. Monthly schedules are computed from it
-- rather than from the previous run, so one that starts on the 31st lands on
-- the last day of shorter months and comes back to the 31st afterwards.
CREATE TABLE recurring_transfer (
    id               UUID         PRIMARY KEY,
    description      VARCHAR(280) NOT NULL,
    from_account_id  UUID         NOT NULL REFERENCES account_replica (id),
    to_account_id    UUID         NOT NULL REFERENCES account_replica (id),
    amount_minor     BIGINT       NOT NULL CHECK (amount_minor > 0),
    recurrence       VARCHAR(16)  NOT NULL CHECK (recurrence IN ('EVERY_MINUTE', 'DAILY', 'WEEKLY', 'MONTHLY')),
    active           BOOLEAN      NOT NULL,
    anchor_at        TIMESTAMPTZ  NOT NULL,
    next_run_at      TIMESTAMPTZ  NOT NULL,
    last_run_at      TIMESTAMPTZ,
    last_run_status  VARCHAR(16)  CHECK (last_run_status IN ('SUCCESS', 'FAILED')),
    last_run_error   VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL,
    CONSTRAINT recurring_transfer_distinct_accounts CHECK (from_account_id <> to_account_id)
);

CREATE INDEX recurring_transfer_due_idx ON recurring_transfer (next_run_at) WHERE active;

-- ShedLock: one row per named lock. The sweep takes the lock so that only
-- one instance sweeps at a time, however many are running.
CREATE TABLE shedlock (
    name        VARCHAR(64)  PRIMARY KEY,
    lock_until  TIMESTAMP(3) NOT NULL,
    locked_at   TIMESTAMP(3) NOT NULL,
    locked_by   VARCHAR(255) NOT NULL
);

CREATE TABLE outbox_event (
    id            UUID         PRIMARY KEY,
    seq           BIGINT       GENERATED ALWAYS AS IDENTITY,
    topic         VARCHAR(200) NOT NULL,
    message_key   VARCHAR(200) NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    payload       JSONB        NOT NULL,
    headers       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    published_at  TIMESTAMPTZ
);

CREATE INDEX outbox_event_unpublished_idx ON outbox_event (seq) WHERE published_at IS NULL;
