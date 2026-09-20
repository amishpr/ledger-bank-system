package io.github.amishpr.ledger.recurring.web;

import io.github.amishpr.ledger.recurring.application.RecurringTransferService;
import io.github.amishpr.ledger.recurring.application.RecurringTransferService.NewSchedule;
import io.github.amishpr.ledger.recurring.application.RecurringTransferService.ScheduleView;
import io.github.amishpr.ledger.recurring.web.RecurringTransferDtos.CreateRequest;
import io.github.amishpr.ledger.recurring.web.RecurringTransferDtos.RecurringTransferResponse;
import io.github.amishpr.ledger.recurring.web.RecurringTransferDtos.UpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recurring-transfers")
@Tag(name = "Recurring transfers", description = "Schedule transfers between asset accounts")
class RecurringTransferController {

    private final RecurringTransferService service;

    RecurringTransferController(RecurringTransferService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List every schedule, paused ones included")
    List<RecurringTransferResponse> list() {
        return service.list().stream().map(RecurringTransferResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one schedule")
    RecurringTransferResponse get(@PathVariable UUID id) {
        return RecurringTransferResponse.from(service.get(id));
    }

    @PostMapping
    @Operation(summary = "Schedule a recurring transfer", description = "Both accounts must be ASSET accounts.")
    ResponseEntity<RecurringTransferResponse> create(@Valid @RequestBody CreateRequest request) {
        ScheduleView created = service.create(new NewSchedule(
                request.description(),
                request.fromAccountId(),
                request.toAccountId(),
                request.amountMinor().value(),
                request.interval(),
                request.startAt()));
        return ResponseEntity.created(URI.create("/api/v1/recurring-transfers/" + created.transfer().getId()))
                .body(RecurringTransferResponse.from(created));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Pause or resume a schedule", description = "Idempotent: sending the same body twice is harmless.")
    RecurringTransferResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRequest request) {
        return RecurringTransferResponse.from(service.setActive(id, request.active()));
    }

    @PostMapping("/{id}/toggle-active")
    @Operation(
            summary = "Flip a schedule between paused and running",
            description = "Kept for the dashboard, which was written against it. New clients should PATCH instead.",
            deprecated = true)
    RecurringTransferResponse toggle(@PathVariable UUID id) {
        return RecurringTransferResponse.from(service.toggle(id));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Cancel a schedule", description = "Transfers it already posted are not undone.")
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
