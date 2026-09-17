-- A read model, not a source of truth. Everything here can be thrown away
-- and rebuilt by replaying ledger.transactions.v1 from the beginning.

CREATE TABLE spending_by_category (
    account_id    UUID         PRIMARY KEY,
    account_name  VARCHAR(120) NOT NULL,
    total_minor   BIGINT       NOT NULL CHECK (total_minor >= 0),
    updated_at    TIMESTAMPTZ  NOT NULL
);

CREATE TABLE spending_by_month (
    month        CHAR(7)     PRIMARY KEY CHECK (month ~ '^\d{4}-\d{2}$'),
    total_minor  BIGINT      NOT NULL CHECK (total_minor >= 0),
    updated_at   TIMESTAMPTZ NOT NULL
);

-- One row per event this service has applied. Inserting the event id in the
-- same transaction as the projection update is what makes a redelivered
-- event a no-op instead of being counted twice.
CREATE TABLE processed_event (
    event_id      UUID         PRIMARY KEY,
    event_type    VARCHAR(100) NOT NULL,
    processed_at  TIMESTAMPTZ  NOT NULL
);
