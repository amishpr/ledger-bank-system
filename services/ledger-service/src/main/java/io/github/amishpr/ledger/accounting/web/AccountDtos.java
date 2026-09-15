package io.github.amishpr.ledger.accounting.web;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.github.amishpr.ledger.accounting.application.AccountService.AccountWithBalance;
import io.github.amishpr.ledger.accounting.application.StatementService.StatementLine;
import io.github.amishpr.ledger.accounting.domain.Account;
import io.github.amishpr.ledger.accounting.domain.AccountType;
import io.github.amishpr.ledger.accounting.domain.EntryDirection;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

final class AccountDtos {

    private AccountDtos() {}

    record CreateAccountRequest(
            @NotBlank @Size(max = 120) @Schema(example = "Checking - Alex") String name,
            @NotNull AccountType type,
            @Pattern(regexp = "[A-Z]{3}", message = "must be a three letter ISO 4217 code such as USD")
                    @Schema(example = "USD", description = "Defaults to USD")
                    String currency) {}

    record AccountResponse(
            UUID id,
            String name,
            AccountType type,
            String currency,
            Instant createdAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING)
                    @Schema(type = "string", example = "250000", description = "Current balance in cents")
                    long balanceMinor) {

        static AccountResponse from(AccountWithBalance a) {
            return from(a.account(), a.balanceMinor());
        }

        static AccountResponse from(Account a, long balanceMinor) {
            return new AccountResponse(a.getId(), a.getName(), a.getType(), a.getCurrency(), a.getCreatedAt(), balanceMinor);
        }
    }

    record StatementLineResponse(
            UUID entryId,
            UUID transactionId,
            String description,
            EntryDirection direction,
            @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "1050") long amountMinor,
            @JsonFormat(shape = JsonFormat.Shape.STRING) @Schema(type = "string", example = "248950")
                    long runningBalanceMinor,
            Instant createdAt) {

        static StatementLineResponse from(StatementLine line) {
            return new StatementLineResponse(
                    line.entryId(),
                    line.transactionId(),
                    line.description(),
                    line.direction(),
                    line.amountMinor(),
                    line.runningBalanceMinor(),
                    line.createdAt());
        }
    }
}
