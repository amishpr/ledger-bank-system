package io.github.amishpr.ledger.recurring.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Every call this service makes to the ledger goes through here, wrapped in
 * a retry around a circuit breaker.
 *
 * <ul>
 *   <li>Only transient failures are retried: the ledger could not be reached,
 *       timed out, or answered 5xx. Retrying a POST is safe here because every
 *       posting carries an idempotency key.
 *   <li>A 4xx answer is the ledger saying no (insufficient funds and the like).
 *       It is not retried and does not count against the ledger's health.
 *   <li>When most recent calls have failed, the circuit opens and calls fail
 *       straight away for a while, instead of stacking up behind timeouts.
 * </ul>
 */
@Component
public class LedgerGateway {

    /** What happened when the sweep asked the ledger to post an occurrence. */
    public sealed interface PostingOutcome {
        record Posted(LedgerClient.PostResult result) implements PostingOutcome {}

        record Rejected(String code, String message) implements PostingOutcome {}

        record Unavailable(String reason) implements PostingOutcome {}
    }

    /** The ledger could not be reached, so the question could not be answered. */
    public static class LedgerUnavailableException extends RuntimeException {
        public LedgerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final LedgerClient client;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    LedgerGateway(LedgerClient client, LedgerResilienceProperties properties, MeterRegistry meters) {
        this.client = client;

        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(properties.failureRateThreshold())
                .slidingWindowSize(properties.slidingWindowSize())
                .minimumNumberOfCalls(Math.min(5, properties.slidingWindowSize()))
                .waitDurationInOpenState(properties.openStateDuration())
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordException(LedgerGateway::isTransient)
                .build());
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(properties.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(properties.initialBackoff(), 2.0, 0.25))
                .retryOnException(LedgerGateway::isTransient)
                .build());
        this.circuitBreaker = breakers.circuitBreaker("ledger");
        this.retry = retries.retry("ledger");
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(meters);
        TaggedRetryMetrics.ofRetryRegistry(retries).bindTo(meters);
    }

    public PostingOutcome post(String idempotencyKey, LedgerClient.PostRequest request) {
        try {
            return new PostingOutcome.Posted(call(() -> client.post(idempotencyKey, request)));
        } catch (HttpClientErrorException e) {
            if (isTransient(e)) {
                return new PostingOutcome.Unavailable("The ledger is rate limiting; will retry on the next sweep");
            }
            LedgerClient.Problem problem = problemOf(e);
            return new PostingOutcome.Rejected(problem.code(), problem.detail());
        } catch (CallNotPermittedException e) {
            return new PostingOutcome.Unavailable("The ledger is unavailable (circuit open); will retry on the next sweep");
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                return new PostingOutcome.Unavailable("The ledger did not respond; will retry on the next sweep");
            }
            throw e;
        }
    }

    /** Empty when the ledger says the account does not exist. */
    public Optional<LedgerClient.Account> findAccount(UUID id) {
        try {
            return Optional.of(call(() -> client.account(id)));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (CallNotPermittedException e) {
            throw new LedgerUnavailableException("Circuit open", e);
        } catch (RuntimeException e) {
            if (isTransient(e)) {
                throw new LedgerUnavailableException("Ledger did not respond", e);
            }
            throw e;
        }
    }

    public CircuitBreaker.State circuitState() {
        return circuitBreaker.getState();
    }

    private <T> T call(Supplier<T> request) {
        return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, request)).get();
    }

    static boolean isTransient(Throwable e) {
        return e instanceof ResourceAccessException
                || e instanceof HttpServerErrorException
                || (e instanceof HttpClientErrorException client
                        && client.getStatusCode().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS));
    }

    private static LedgerClient.Problem problemOf(HttpClientErrorException e) {
        try {
            LedgerClient.Problem problem = e.getResponseBodyAs(LedgerClient.Problem.class);
            if (problem != null && problem.code() != null) {
                return problem;
            }
        } catch (RuntimeException ignored) {
            // Not a Problem Details body; fall back to the status line below.
        }
        return new LedgerClient.Problem("HTTP_" + e.getStatusCode().value(), e.getStatusText());
    }
}
