package io.github.amishpr.ledger.recurring.application;

import io.github.amishpr.ledger.events.AccountCreated;
import io.github.amishpr.ledger.events.AccountSnapshot;
import io.github.amishpr.ledger.events.IntegrationEvent;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Keeps the account replica current. This is event carried state transfer:
 * the event holds everything the replica needs, so there is no call back to
 * the ledger, and the upsert makes a redelivered event harmless.
 */
@Component
class AccountReplicationListener {

    private final IntegrationEventCodec codec;
    private final AccountDirectory directory;

    AccountReplicationListener(IntegrationEventCodec codec, AccountDirectory directory) {
        this.codec = codec;
        this.directory = directory;
    }

    @KafkaListener(topics = Topics.ACCOUNTS, groupId = "${spring.application.name}")
    void on(String payload) {
        IntegrationEvent event = codec.read(payload);
        if (event instanceof AccountCreated created) {
            AccountSnapshot a = created.account();
            directory.replicate(a.id(), a.name(), a.type(), a.currency(), a.createdAt());
        }
    }
}
