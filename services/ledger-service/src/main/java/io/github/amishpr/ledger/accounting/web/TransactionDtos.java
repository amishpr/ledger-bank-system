package io.github.amishpr.ledger.accounting.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.amishpr.ledger.accounting.application.PostTransactionCommand;
import io.github.amishpr.ledger.accounting.application.PostingResult;
import io.github.amishpr.ledger.accounting.domain.EntryDirection;
import io.github.amishpr.ledger.accounting.domain.JournalEntry;
import io.github.amishpr.ledger.accounting.domain.LedgerTransaction;
import io.github.amishpr.ledger.accounting.domain.Origin;
import io.github.amishpr.ledger.accounting.domain.OriginType;
import io.github.amishpr.ledger.accounting.domain.PostingLine;
import io.github.amishpr.ledger.accounting.domain.TransactionStatus;
import io.github.amishpr.ledger.platform.json.MinorUnits;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class TransactionDtos {

    private TransactionDtos() {}

    record EntryRequest(@NotNull UUID accountId, @NotNull EntryDirection direction, @NotNull MinorUnits amountMinor) {

        PostingLine toLine() {
            return new PostingLine(accountId, direction, amountMinor.value());
        }
    }

    @Schema(description = "Set by internal callers, such as the recurring transfer service, to record where a posting came from")
    record OriginRequest(@NotNull OriginType type, @NotBlank @Size(max = 100) String reference) {}

    record PostTransactionRequest(
            @NotBlank @Size(max = 280) @Schema(example = "Rent split - Alex to Jordan") String description,
            @NotNull @Size(min = 2, max = 100) List<@Valid @NotNull EntryRequest> entries,
            @Size(min = 1, max = 255)
                    @Schema(description = "Prefer the Idempotency-Key header. Kept for clients written against the first API")
                    String idempotencyKey,
            @Valid OriginRequest origin) {

        PostTransactionCommand toCommand(String key) {
            return new PostTransactionCommand(
                    description.strip(),
                    entries.stream().map(EntryRequest::toLine).toList(),
                    key,
                    origin == null ? null : new Origin(origin.type(), origin.reference()));
        }
    }

    record ReverseTransactionRequest(@Size(min = 1, max = 280) String note) {}

    record EntryResponse(
            UUID id,
            UUID transactionId,
            UUID accountId,
            EntryDirection direction,
            @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "1050") long amountMinor,
            Instant createdAt) {

        static EntryResponse from(JournalEntry e) {
            return new EntryResponse(e.getId(), e.getTransactionId(), e.getAccountId(), e.getDirection(), e.getAmountMinor(), e.getCreatedAt());
        }
    }

    record OriginResponse(OriginType type, String reference) {}

    record TransactionResponse(
            UUID id,
            String description,
            TransactionStatus status,
            String idempotencyKey,
            Instant createdAt,
            @JsonInclude(JsonInclude.Include.NON_NULL) OriginResponse origin,
            @JsonInclude(JsonInclude.Include.NON_NULL) UUID reversalOf,
            List<EntryResponse> entries) {

        static TransactionResponse from(LedgerTransaction tx) {
            Origin origin = tx.getOrigin();
            return new TransactionResponse(
                    tx.getId(),
                    tx.getDescription(),
                    tx.getStatus(),
                    tx.getIdempotencyKey(),
                    tx.getCreatedAt(),
                    origin == null ? null : new OriginResponse(origin.type(), origin.reference()),
                    tx.getReversalOf(),
                    tx.getEntries().stream().map(EntryResponse::from).toList());
        }
    }

    record PostResultResponse(TransactionResponse transaction, boolean replayed, List<UUID> affectedAccountIds) {

        static PostResultResponse from(PostingResult result) {
            return new PostResultResponse(
                    TransactionResponse.from(result.transaction()), result.replayed(), result.affectedAccountIds());
        }
    }
}
