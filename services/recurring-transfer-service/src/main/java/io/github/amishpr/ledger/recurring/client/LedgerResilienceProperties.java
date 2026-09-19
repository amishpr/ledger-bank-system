package io.github.amishpr.ledger.recurring.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How calls to the ledger are retried and when to stop calling it for a
 * while, under {@code recurring.ledger}.
 *
 * @param maxAttempts total attempts per call, the first one included
 * @param initialBackoff wait before the first retry, doubled each time after
 * @param failureRateThreshold percentage of failed calls that opens the circuit
 * @param slidingWindowSize how many recent calls the failure rate is measured over
 * @param openStateDuration how long the circuit stays open before letting a trial call through
 */
@ConfigurationProperties("recurring.ledger")
public record LedgerResilienceProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("200ms") Duration initialBackoff,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("10") int slidingWindowSize,
        @DefaultValue("30s") Duration openStateDuration) {}
