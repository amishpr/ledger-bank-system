package io.github.amishpr.ledger.platform.outbox;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings for the transactional outbox, under {@code ledger.outbox}. A service
 * opts in with {@code ledger.outbox.enabled=true} once its schema has the
 * {@code outbox_event} table.
 *
 * @param enabled whether this service writes and relays outbox events
 * @param pollInterval how long the relay waits between sweeps of unpublished rows
 * @param batchSize the most rows one sweep claims and publishes
 * @param sendTimeout how long the relay waits for Kafka to acknowledge a batch
 * @param retention how long published rows are kept before they are deleted
 */
@Validated
@ConfigurationProperties("ledger.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("250ms") Duration pollInterval,
        @DefaultValue("100") @Min(1) @Max(1000) int batchSize,
        @DefaultValue("10s") Duration sendTimeout,
        @DefaultValue("7d") Duration retention) {

    public OutboxProperties {
        requirePositive(pollInterval, "poll-interval");
        requirePositive(sendTimeout, "send-timeout");
        requirePositive(retention, "retention");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("ledger.outbox." + name + " must be a positive duration");
        }
    }
}
