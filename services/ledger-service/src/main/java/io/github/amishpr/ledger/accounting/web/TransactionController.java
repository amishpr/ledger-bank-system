package io.github.amishpr.ledger.accounting.web;

import io.github.amishpr.ledger.accounting.application.PostingResult;
import io.github.amishpr.ledger.accounting.application.PostingService;
import io.github.amishpr.ledger.accounting.domain.LedgerException;
import io.github.amishpr.ledger.accounting.web.TransactionDtos.PostResultResponse;
import io.github.amishpr.ledger.accounting.web.TransactionDtos.PostTransactionRequest;
import io.github.amishpr.ledger.accounting.web.TransactionDtos.ReverseTransactionRequest;
import io.github.amishpr.ledger.accounting.web.TransactionDtos.TransactionResponse;
import io.github.amishpr.ledger.platform.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Post balanced double entry transactions and reverse them")
class TransactionController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final PostingService posting;

    TransactionController(PostingService posting) {
        this.posting = posting;
    }

    @PostMapping
    @Operation(
            summary = "Post a transaction",
            description = """
                    Debits must equal credits, amounts are whole cents, and an ASSET account can never go \
                    below zero. Send an Idempotency-Key so a retry after a timeout can never post twice: \
                    the same key with the same body returns the original transaction with 200 and an \
                    Idempotent-Replayed header, and the same key with a different body is a 409.""")
    @ApiResponse(responseCode = "201", description = "Posted")
    @ApiResponse(responseCode = "200", description = "Replayed an earlier request with the same idempotency key")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "INSUFFICIENT_FUNDS or IDEMPOTENCY_CONFLICT", content = @Content(
            mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "UNBALANCED_TRANSACTION, INVALID_AMOUNT or CURRENCY_MISMATCH",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    ResponseEntity<PostResultResponse> post(
            @Parameter(description = "Unique per logical request, for example a UUID made by the client")
                    @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Size(min = 1, max = 255) String idempotencyKey,
            @Valid @RequestBody PostTransactionRequest request) {
        String key = resolveKey(idempotencyKey, request.idempotencyKey());
        PostingResult result = posting.post(request.toCommand(key));
        PostResultResponse body = PostResultResponse.from(result);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(body);
        }
        return ResponseEntity.created(location(result)).body(body);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one transaction and its entries")
    TransactionResponse get(@PathVariable UUID id) {
        return posting.find(id).map(TransactionResponse::from).orElseThrow(() -> LedgerException.transactionNotFound(id));
    }

    @PostMapping("/{id}/reverse")
    @Operation(
            summary = "Reverse a transaction",
            description = """
                    Posts the opposite entries as a new transaction and marks the original VOIDED. \
                    Nothing already posted is ever edited or deleted.""")
    ResponseEntity<PostResultResponse> reverse(
            @PathVariable UUID id, @Valid @RequestBody(required = false) ReverseTransactionRequest request) {
        PostingResult result = posting.reverse(id, request == null ? null : request.note());
        return ResponseEntity.created(location(result)).body(PostResultResponse.from(result));
    }

    private static URI location(PostingResult result) {
        return URI.create("/api/v1/transactions/" + result.transaction().getId());
    }

    private static String resolveKey(String header, String body) {
        if (header != null && body != null && !header.equals(body)) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "The Idempotency-Key header and the idempotencyKey field disagree. Send one or make them match.");
        }
        return header != null ? header : body;
    }
}
