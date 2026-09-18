package io.github.amishpr.ledger.notification;

import io.github.amishpr.ledger.events.IntegrationEvent;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Relays events to browsers. Every instance needs every event, because each
 * holds a different set of sockets, so each instance joins its own consumer
 * group (a random suffix) instead of sharing one and splitting the partitions.
 * It starts from the latest offset: a dashboard that connects now needs what
 * happens next, not a replay of history it will load over REST anyway.
 */
@Component
class EventFanout {

    private static final Logger log = LoggerFactory.getLogger(EventFanout.class);

    private final IntegrationEventCodec codec;
    private final LedgerSocketHandler sockets;

    EventFanout(IntegrationEventCodec codec, LedgerSocketHandler sockets) {
        this.codec = codec;
        this.sockets = sockets;
    }

    @KafkaListener(
            id = "browser-fanout",
            topics = {Topics.TRANSACTIONS, Topics.RECURRING_TRANSFERS},
            groupId = "${spring.application.name}-${random.uuid}",
            properties = "auto.offset.reset=latest")
    void on(String payload) {
        IntegrationEvent event = codec.read(payload);
        BrowserMessages.forEvent(event).ifPresent(message -> {
            log.debug("Broadcasting {} to {} sockets", event.eventType(), sockets.connectionCount());
            sockets.broadcast(message);
        });
    }
}
