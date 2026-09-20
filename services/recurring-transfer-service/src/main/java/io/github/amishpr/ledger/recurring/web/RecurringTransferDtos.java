package io.github.amishpr.ledger.recurring.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.amishpr.ledger.platform.json.MinorUnits;
import io.github.amishpr.ledger.recurring.application.RecurringTransferService.ScheduleView;
import io.github.amishpr.ledger.recurring.domain.AccountReplica;
import io.github.amishpr.ledger.recurring.domain.RecurrenceInterval;
import io.github.amishpr.ledger.recurring.domain.RecurringTransfer;
import io.github.amishpr.ledger.recurring.domain.RunStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

final class RecurringTransferDtos {

    private RecurringTransferDtos() {}

    record CreateRequest(
            @NotBlank @Size(max = 280) @Schema(example = "Automatic savings sweep") String description,
            @NotNull UUID fromAccountId,
            @NotNull UUID toAccountId,
            @NotNull MinorUnits amountMinor,
            @NotNull RecurrenceInterval interval,
            @Schema(description = "First occurrence. Defaults to now, which makes it due on the next sweep")
                    Instant startAt) {}

    record UpdateRequest(@NotNull Boolean active) {}

    record AccountSummary(UUID id, String name, String type, String currency, Instant createdAt) {

        static AccountSummary from(AccountReplica a) {
            return a == null ? null : new AccountSummary(a.getId(), a.getName(), a.getType(), a.getCurrency(), a.getCreatedAt());
        }
    }

    record RecurringTransferResponse(
            UUID id,
            String description,
            UUID fromAccountId,
            AccountSummary fromAccount,
            UUID toAccountId,
            AccountSummary toAccount,
            @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "5000") long amountMinor,
            RecurrenceInterval interval,
            boolean active,
            Instant nextRunAt,
            Instant lastRunAt,
            RunStatus lastRunStatus,
            String lastRunError,
            Instant createdAt) {

        static RecurringTransferResponse from(ScheduleView view) {
            RecurringTransfer t = view.transfer();
            return new RecurringTransferResponse(
                    t.getId(),
                    t.getDescription(),
                    t.getFromAccountId(),
                    AccountSummary.from(view.from()),
                    t.getToAccountId(),
                    AccountSummary.from(view.to()),
                    t.getAmountMinor(),
                    t.getInterval(),
                    t.isActive(),
                    t.getNextRunAt(),
                    t.getLastRunAt(),
                    t.getLastRunStatus(),
                    t.getLastRunError(),
                    t.getCreatedAt());
        }
    }
}
