-- Ledger schema. Money is BIGINT cents, never a decimal or a float, and a
-- balance is never stored: it is always the sum of an account's entries.

CREATE TABLE account (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    type        VARCHAR(16)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency    VARCHAR(3)   NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    created_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX account_type_idx ON account (type);

-- Long enough for a reversal's generated description, which quotes the
-- original. User supplied descriptions are limited to 280 by the API.
CREATE TABLE ledger_transaction (
    id                UUID         PRIMARY KEY,
    description       VARCHAR(500) NOT NULL,
    status            VARCHAR(16)  NOT NULL CHECK (status IN ('POSTED', 'VOIDED')),
    idempotency_key   VARCHAR(255) UNIQUE,
    request_hash      VARCHAR(64),
    origin_type       VARCHAR(40),
    origin_reference  VARCHAR(100),
    reversal_of       UUID         REFERENCES ledger_transaction (id),
    created_at        TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ledger_transaction_key_has_hash CHECK ((idempotency_key IS NULL) = (request_hash IS NULL)),
    CONSTRAINT ledger_transaction_origin_complete CHECK ((origin_type IS NULL) = (origin_reference IS NULL))
);

CREATE INDEX ledger_transaction_reversal_of_idx ON ledger_transaction (reversal_of);

-- seq is the journal sequence number: a strictly increasing posting order
-- that breaks ties between entries written in the same instant.
CREATE TABLE journal_entry (
    id              UUID        PRIMARY KEY,
    seq             BIGINT      GENERATED ALWAYS AS IDENTITY UNIQUE,
    transaction_id  UUID        NOT NULL REFERENCES ledger_transaction (id),
    line_number     SMALLINT    NOT NULL,
    account_id      UUID        NOT NULL REFERENCES account (id),
    direction       VARCHAR(6)  NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    created_at      TIMESTAMPTZ NOT NULL,
    UNIQUE (transaction_id, line_number)
);

-- Covers both the balance sum and the statement's window function, so
-- either can be answered from the index alone.
CREATE INDEX journal_entry_account_idx ON journal_entry (account_id, created_at, seq) INCLUDE (direction, amount_minor);

CREATE TABLE audit_log (
    id              UUID         PRIMARY KEY,
    entity_type     VARCHAR(40)  NOT NULL,
    entity_id       UUID         NOT NULL,
    action          VARCHAR(40)  NOT NULL,
    metadata        JSONB        NOT NULL,
    transaction_id  UUID         REFERENCES ledger_transaction (id),
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX audit_log_entity_idx ON audit_log (entity_type, entity_id);

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

-- The ledger is append-only, and the database enforces it rather than
-- trusting every code path to remember. Entries can never be changed or
-- deleted. A transaction can change in exactly one way: POSTED to VOIDED,
-- when it is reversed.

CREATE FUNCTION journal_entry_is_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'journal entries are append-only (attempted % on %)', TG_OP, OLD.id
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entry_append_only
    BEFORE UPDATE OR DELETE ON journal_entry
    FOR EACH ROW EXECUTE FUNCTION journal_entry_is_append_only();

CREATE FUNCTION ledger_transaction_only_voids() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger transactions cannot be deleted (%)', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.id, NEW.description, NEW.idempotency_key, NEW.request_hash, NEW.origin_type,
        NEW.origin_reference, NEW.reversal_of, NEW.created_at)
       IS DISTINCT FROM
       (OLD.id, OLD.description, OLD.idempotency_key, OLD.request_hash, OLD.origin_type,
        OLD.origin_reference, OLD.reversal_of, OLD.created_at)
       OR NOT (OLD.status = 'POSTED' AND NEW.status = 'VOIDED') THEN
        RAISE EXCEPTION 'a ledger transaction can only change from POSTED to VOIDED (%)', OLD.id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_transaction_only_voids
    BEFORE UPDATE OR DELETE ON ledger_transaction
    FOR EACH ROW EXECUTE FUNCTION ledger_transaction_only_voids();
