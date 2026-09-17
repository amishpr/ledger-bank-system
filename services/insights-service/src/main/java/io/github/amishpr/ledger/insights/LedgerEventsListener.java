package io.github.amishpr.ledger.insights;

import io.github.amishpr.ledger.events.IntegrationEvent;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.events.TransactionPosted;
import io.github.amishpr.ledger.events.TransactionReversed;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Feeds the spending projection from the ledger's transaction topic. A record
 * that cannot be read is not retried (retrying will not fix it); the shared
 * error handler sends it to this service's dead letter topic so the partition
 * keeps moving.
 */
@Component
class LedgerEventsListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerEventsListener.class);

    private final IntegrationEventCodec codec;
    private final SpendingProjection projection;
    private final MeterRegistry meters;

    LedgerEventsListener(IntegrationEventCodec codec, SpendingProjection projection, MeterRegistry meters) {
        this.codec = codec;
        this.projection = projection;
        this.meters = meters;
    }

    @KafkaListener(topics = Topics.TRANSACTIONS, groupId = "${spring.application.name}")
    void on(String payload) {
        IntegrationEvent event = codec.read(payload);
        boolean applied = switch (event) {
            case TransactionPosted posted -> projection.apply(posted.eventId(), posted.eventType(), posted.transaction());
            case TransactionReversed reversed ->
                    projection.apply(reversed.eventId(), reversed.eventType(), reversed.transaction());
            default -> {
                log.debug("Ignoring {} on {}", event.eventType(), Topics.TRANSACTIONS);
                yield false;
            }
        };
        meters.counter("insights.events", "type", event.eventType(), "outcome", applied ? "applied" : "skipped")
                .increment();
    }
}
