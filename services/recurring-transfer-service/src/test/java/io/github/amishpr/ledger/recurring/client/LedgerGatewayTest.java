package io.github.amishpr.ledger.recurring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.github.amishpr.ledger.recurring.client.LedgerGateway.PostingOutcome;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** The retry and circuit breaker policy, against a scripted ledger. */
class LedgerGatewayTest {

    private static final String POSTED = """
            {"transaction":{"id":"%s","description":"Sweep","status":"POSTED","idempotencyKey":"k",
             "createdAt":"2026-10-01T12:00:00Z","entries":[]},"replayed":false,"affectedAccountIds":[]}
            """.formatted(UUID.randomUUID());

    private MockRestServiceServer ledger;
    private LedgerGateway gateway;

    private final LedgerClient.PostRequest request = new LedgerClient.PostRequest(
            "Sweep",
            List.of(new LedgerClient.Entry(UUID.randomUUID(), "CREDIT", "500"), new LedgerClient.Entry(UUID.randomUUID(), "DEBIT", "500")),
            new LedgerClient.Origin("RECURRING_TRANSFER", "s-1"));

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://ledger");
        ledger = MockRestServiceServer.bindTo(builder).build();
        LedgerClient client = HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
                .build()
                .createClient(LedgerClient.class);
        gateway = new LedgerGateway(
                client,
                new LedgerResilienceProperties(3, Duration.ofMillis(1), 50, 4, Duration.ofMinutes(1)),
                new SimpleMeterRegistry());
    }

    @Test
    void postsWithTheIdempotencyKeyHeader() {
        ledger.expect(once(), requestTo("http://ledger/api/v1/transactions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Idempotency-Key", "recurring:s-1:2026-10-01T12:00:00Z"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(POSTED));

        PostingOutcome outcome = gateway.post("recurring:s-1:2026-10-01T12:00:00Z", request);

        assertThat(outcome).isInstanceOf(PostingOutcome.Posted.class);
        ledger.verify();
    }

    @Test
    void reportsABusinessRejectionWithoutRetrying() {
        ledger.expect(once(), requestTo("http://ledger/api/v1/transactions"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body("""
                        {"status":409,"code":"INSUFFICIENT_FUNDS","detail":"Account abc has insufficient funds"}
                        """));

        PostingOutcome outcome = gateway.post("k", request);

        assertThat(outcome).isEqualTo(new PostingOutcome.Rejected("INSUFFICIENT_FUNDS", "Account abc has insufficient funds"));
        ledger.verify();
    }

    @Test
    void retriesATransientFailureAndSucceeds() {
        ledger.expect(times(2), requestTo("http://ledger/api/v1/transactions")).andRespond(withServiceUnavailable());
        ledger.expect(once(), requestTo("http://ledger/api/v1/transactions"))
                .andRespond(withStatus(HttpStatus.CREATED).contentType(MediaType.APPLICATION_JSON).body(POSTED));

        assertThat(gateway.post("k", request)).isInstanceOf(PostingOutcome.Posted.class);
        ledger.verify();
    }

    @Test
    void givesUpAfterThreeAttemptsAndThenStopsCallingAltogether() {
        ledger.expect(times(3), requestTo("http://ledger/api/v1/transactions")).andRespond(withServiceUnavailable());
        ledger.expect(times(1), requestTo("http://ledger/api/v1/transactions")).andRespond(withServiceUnavailable());

        assertThat(gateway.post("k", request)).isInstanceOf(PostingOutcome.Unavailable.class);
        assertThat(gateway.post("k", request)).isInstanceOf(PostingOutcome.Unavailable.class);

        // Four failures out of four recorded calls: the circuit is open, so the
        // third posting fails at once without a request reaching the ledger.
        assertThat(gateway.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(gateway.post("k", request)).isInstanceOfSatisfying(PostingOutcome.Unavailable.class,
                u -> assertThat(u.reason()).contains("circuit open"));
        ledger.verify();
    }

    @Test
    void findsNothingWhenTheLedgerSaysTheAccountDoesNotExist() {
        UUID id = UUID.randomUUID();
        ledger.expect(once(), requestTo("http://ledger/api/v1/accounts/" + id)).andRespond(withResourceNotFound());

        assertThat(gateway.findAccount(id)).isEmpty();
    }

    @Test
    void readsAnAccount() {
        UUID id = UUID.randomUUID();
        ledger.expect(once(), requestTo("http://ledger/api/v1/accounts/" + id)).andRespond(withSuccess("""
                {"id":"%s","name":"Checking - Alex","type":"ASSET","currency":"USD",
                 "createdAt":"2026-01-01T00:00:00Z","balanceMinor":"100"}
                """.formatted(id), MediaType.APPLICATION_JSON));

        assertThat(gateway.findAccount(id)).get().extracting(LedgerClient.Account::name).isEqualTo("Checking - Alex");
    }
}
