CREATE TABLE IF NOT EXISTS outbox_event (
    id           UUID PRIMARY KEY,
    seq          BIGINT GENERATED ALWAYS AS IDENTITY,
    topic        VARCHAR(200) NOT NULL,
    message_key  VARCHAR(200) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSONB        NOT NULL,
    headers      JSONB        NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ
);
