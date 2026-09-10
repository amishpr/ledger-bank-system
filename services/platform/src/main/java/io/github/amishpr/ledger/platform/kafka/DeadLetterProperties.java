package io.github.amishpr.ledger.platform.kafka;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How Kafka consumers retry and give up, under {@code ledger.kafka.consumer}.
 *
 * @param deadLetterEnabled send records that keep failing to a dead letter topic
 * @param maxRetries retries after the first attempt before a record is dead lettered
 * @param initialInterval wait before the first retry, doubling each time
 * @param maxInterval the longest wait between retries
 */
@ConfigurationProperties("ledger.kafka.consumer")
public record DeadLetterProperties(
        @DefaultValue("true") boolean deadLetterEnabled,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("500ms") Duration initialInterval,
        @DefaultValue("5s") Duration maxInterval) {}
