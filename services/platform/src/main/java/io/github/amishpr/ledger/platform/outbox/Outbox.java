package io.github.amishpr.ledger.platform.outbox;

import io.github.amishpr.ledger.events.IntegrationEvent;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records an event in the {@code outbox_event} table as part of the caller's
 * database transaction. The event and the business change it describes
 * commit together or not at all, which is the whole point: there is no
 * window where the database changed but Kafka never heard about it, or Kafka
 * heard about a change that was rolled back. {@link OutboxRelay} publishes the
 * rows afterwards.
 *
 * <p>The schema each service creates for this (see its Flyway migrations):
 *
 * <pre>
 * CREATE TABLE outbox_event (
 *     id            UUID PRIMARY KEY,
 *     seq           BIGINT GENERATED ALWAYS AS IDENTITY,
 *     topic         VARCHAR(200) NOT NULL,
 *     message_key   VARCHAR(200) NOT NULL,
 *     event_type    VARCHAR(100) NOT NULL,
 *     payload       JSONB        NOT NULL,
 *     headers       JSONB        NOT NULL,
 *     created_at    TIMESTAMPTZ  NOT NULL,
 *     published_at  TIMESTAMPTZ
 * );
 * </pre>
 */
public class Outbox {

    private final JdbcClient jdbc;
    private final IntegrationEventCodec codec;
    private final JsonMapper jsonMapper;
    private final TraceContextCapture traceContext;
    private final Clock clock;

    public Outbox(
            JdbcClient jdbc,
            IntegrationEventCodec codec,
            JsonMapper jsonMapper,
            TraceContextCapture traceContext,
            Clock clock) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.jsonMapper = jsonMapper;
        this.traceContext = traceContext;
        this.clock = clock;
    }

    /**
     * Must be called inside an existing transaction. Failing loudly without one
     * is deliberate, since an event written on its own would defeat the outbox.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(String topic, IntegrationEvent event) {
        Map<String, String> headers = traceContext.capture();
        jdbc.sql("""
                INSERT INTO outbox_event (id, topic, message_key, event_type, payload, headers, created_at)
                VALUES (:id, :topic, :key, :type, CAST(:payload AS JSONB), CAST(:headers AS JSONB), :createdAt)
                """)
                .param("id", event.eventId() == null ? UUID.randomUUID() : event.eventId())
                .param("topic", topic)
                .param("key", event.aggregateId())
                .param("type", event.eventType())
                .param("payload", codec.write(event))
                .param("headers", jsonMapper.writeValueAsString(headers))
                .param("createdAt", Timestamp.from(clock.instant()))
                .update();
    }
}
