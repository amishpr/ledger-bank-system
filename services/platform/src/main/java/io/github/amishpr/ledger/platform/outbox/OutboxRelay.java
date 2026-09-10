package io.github.amishpr.ledger.platform.outbox;

import io.github.amishpr.ledger.events.Topics;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes rows written by {@link Outbox} to Kafka and marks them sent.
 *
 * <p>Each sweep claims a batch with {@code FOR UPDATE SKIP LOCKED}, so any
 * number of service instances can relay at once without two of them taking the
 * same row. A batch is only marked published after Kafka has acknowledged every
 * record in it. If anything fails, the transaction rolls back and the batch is
 * retried on a later sweep, which can publish a record twice. That makes
 * delivery at least once, and is why every consumer deduplicates on the event id.
 *
 * <p>When Kafka is unreachable the relay backs off exponentially, up to 30
 * seconds between attempts, instead of retrying four times a second.
 */
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final TypeReference<Map<String, String>> HEADER_MAP = new TypeReference<>() {};

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final KafkaTemplate<String, String> kafka;
    private final JsonMapper jsonMapper;
    private final TraceContextCapture traceContext;
    private final OutboxProperties properties;
    private final Clock clock;

    private final AtomicLong published = new AtomicLong();
    private final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    private final List<ScheduledFuture<?>> scheduled = new ArrayList<>();
    private volatile boolean running;

    private Duration backoff = Duration.ZERO;
    private Instant nextAttemptAt = Instant.MIN;

    public OutboxRelay(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            KafkaTemplate<String, String> kafka,
            JsonMapper jsonMapper,
            TraceContextCapture traceContext,
            OutboxProperties properties,
            Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.kafka = kafka;
        this.jsonMapper = jsonMapper;
        this.traceContext = traceContext;
        this.properties = properties;
        this.clock = clock;
        scheduler.setThreadNamePrefix("outbox-relay-");
        scheduler.setPoolSize(1);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
    }

    /**
     * Publishes everything that is waiting, one batch at a time, and returns how
     * many events went out. The scheduler calls this on every sweep, and tests
     * call it directly instead of waiting on a timer.
     */
    public int relayPending() {
        int total = 0;
        while (true) {
            Integer sent = transactions.execute(status -> publishBatch());
            int count = sent == null ? 0 : sent;
            total += count;
            if (count < properties.batchSize()) {
                return total;
            }
        }
    }

    /** Deletes rows that were published longer ago than the retention period. */
    public int purgePublished() {
        Instant cutoff = clock.instant().minus(properties.retention());
        return jdbc.sql("DELETE FROM outbox_event WHERE published_at IS NOT NULL AND published_at < :cutoff")
                .param("cutoff", Timestamp.from(cutoff))
                .update();
    }

    public long pendingCount() {
        return jdbc.sql("SELECT count(*) FROM outbox_event WHERE published_at IS NULL")
                .query(Long.class)
                .single();
    }

    public long publishedCount() {
        return published.get();
    }

    private int publishBatch() {
        List<PendingEvent> batch = jdbc.sql("""
                SELECT id, topic, message_key, event_type, payload::text AS payload, headers::text AS headers
                FROM outbox_event
                WHERE published_at IS NULL
                ORDER BY seq
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", properties.batchSize())
                .query((rs, row) -> new PendingEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("topic"),
                        rs.getString("message_key"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getString("headers")))
                .list();
        if (batch.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<SendResult<String, String>>> acks = new ArrayList<>(batch.size());
        for (PendingEvent event : batch) {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>(event.topic(), null, event.key(), event.payload());
            record.headers().add(Topics.HEADER_EVENT_TYPE, event.type().getBytes(StandardCharsets.UTF_8));
            record.headers().add(Topics.HEADER_EVENT_ID, event.id().toString().getBytes(StandardCharsets.UTF_8));
            traceContext.runWithin(parseHeaders(event.headers()), "outbox publish", () -> acks.add(kafka.send(record)));
        }
        awaitAll(acks);

        jdbc.sql("UPDATE outbox_event SET published_at = :now WHERE id IN (:ids)")
                .param("now", Timestamp.from(clock.instant()))
                .param("ids", batch.stream().map(PendingEvent::id).toList())
                .update();
        published.addAndGet(batch.size());
        return batch.size();
    }

    private void awaitAll(List<CompletableFuture<SendResult<String, String>>> acks) {
        try {
            CompletableFuture.allOf(acks.toArray(CompletableFuture[]::new))
                    .get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OutboxPublishException("Interrupted while waiting for Kafka", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new OutboxPublishException("Kafka did not acknowledge the outbox batch", e);
        }
    }

    private Map<String, String> parseHeaders(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return jsonMapper.readValue(json, HEADER_MAP);
    }

    private void sweep() {
        Instant now = clock.instant();
        if (now.isBefore(nextAttemptAt)) {
            return;
        }
        try {
            relayPending();
            if (!backoff.isZero()) {
                log.info("Outbox relay recovered, publishing again");
            }
            backoff = Duration.ZERO;
        } catch (RuntimeException e) {
            if (backoff.isZero()) {
                log.warn("Outbox relay could not publish, backing off", e);
            } else {
                log.debug("Outbox relay still failing", e);
            }
            Duration doubled = backoff.isZero() ? properties.pollInterval() : backoff.multipliedBy(2);
            backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
            nextAttemptAt = now.plus(backoff);
        }
    }

    private void purge() {
        try {
            int removed = purgePublished();
            if (removed > 0) {
                log.debug("Purged {} published outbox rows", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Could not purge published outbox rows: {}", e.getMessage());
        }
    }

    @Override
    public void start() {
        scheduler.initialize();
        scheduled.add(scheduler.scheduleWithFixedDelay(this::sweep, properties.pollInterval()));
        scheduled.add(scheduler.scheduleWithFixedDelay(this::purge, Duration.ofHours(1)));
        running = true;
    }

    @Override
    public void stop() {
        scheduled.forEach(future -> future.cancel(false));
        scheduled.clear();
        scheduler.shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private record PendingEvent(UUID id, String topic, String key, String type, String payload, String headers) {}

    /** Thrown when a batch could not be published. The batch stays in the outbox. */
    public static class OutboxPublishException extends RuntimeException {
        OutboxPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
