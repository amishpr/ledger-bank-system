package io.github.amishpr.ledger.recurring.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * The parts of the ledger service's API this service uses, as a declarative
 * HTTP interface. Spring generates the implementation; the base URL and
 * timeouts come from {@code spring.http.serviceclient.ledger.*}.
 */
@HttpExchange("/api/v1")
public interface LedgerClient {

    @PostExchange("/transactions")
    PostResult post(@RequestHeader("Idempotency-Key") String idempotencyKey, @RequestBody PostRequest request);

    @GetExchange("/accounts/{id}")
    Account account(@PathVariable UUID id);

    record PostRequest(String description, List<Entry> entries, Origin origin) {}

    record Entry(UUID accountId, String direction, String amountMinor) {}

    record Origin(String type, String reference) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PostResult(Transaction transaction, boolean replayed, List<UUID> affectedAccountIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Transaction(UUID id, String description, String status, String idempotencyKey, Instant createdAt, List<PostedEntry> entries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PostedEntry(UUID id, UUID transactionId, UUID accountId, String direction, String amountMinor, Instant createdAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Account(UUID id, String name, String type, String currency, Instant createdAt) {}

    /** The ledger's RFC 9457 error body, reduced to what this service needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Problem(String code, String detail) {}
}
