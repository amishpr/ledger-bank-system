package io.github.amishpr.ledger.accounting.web;

import io.github.amishpr.ledger.accounting.application.AccountService;
import io.github.amishpr.ledger.accounting.application.StatementService;
import io.github.amishpr.ledger.accounting.application.StatementService.Statement;
import io.github.amishpr.ledger.accounting.domain.Account;
import io.github.amishpr.ledger.accounting.web.AccountDtos.AccountResponse;
import io.github.amishpr.ledger.accounting.web.AccountDtos.CreateAccountRequest;
import io.github.amishpr.ledger.accounting.web.AccountDtos.StatementLineResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Open accounts, read balances and statements")
class AccountController {

    /** The full history is one CSV download; this only caps what a JSON page can ask for. */
    static final int MAX_STATEMENT_LIMIT = 1000;

    private final AccountService accounts;
    private final StatementService statements;

    AccountController(AccountService accounts, StatementService statements) {
        this.accounts = accounts;
        this.statements = statements;
    }

    @GetMapping
    @Operation(summary = "List every account with its current balance")
    List<AccountResponse> list() {
        return accounts.list().stream().map(AccountResponse::from).toList();
    }

    @PostMapping
    @Operation(summary = "Open an account", description = "Opens with a zero balance. Fund it by posting a transaction.")
    ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accounts.open(request.name(), request.type(), request.currency());
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.getId()))
                .body(AccountResponse.from(account, 0));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one account and its current balance")
    AccountResponse get(@PathVariable UUID id) {
        return AccountResponse.from(accounts.get(id));
    }

    @GetMapping("/{id}/statement")
    @Operation(summary = "Recent entries for an account, newest first, each with the running balance after it")
    List<StatementLineResponse> statement(
            @PathVariable UUID id,
            @Parameter(description = "How many entries to return")
                    @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_STATEMENT_LIMIT) int limit) {
        return statements.statement(id, limit).lines().stream().map(StatementLineResponse::from).toList();
    }

    @GetMapping(value = "/{id}/statement/export", produces = "text/csv")
    @Operation(summary = "Download the account's full statement as CSV, oldest first")
    ResponseEntity<String> export(@PathVariable UUID id) {
        Statement statement = statements.statement(id, Integer.MAX_VALUE);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(StatementCsv.filenameFor(statement.account().getName()))
                        .build()
                        .toString())
                .body(StatementCsv.build(statement.lines()));
    }
}
