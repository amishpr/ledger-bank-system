package io.github.amishpr.ledger.accounting.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.amishpr.ledger.accounting.application.PostTransactionCommand;
import io.github.amishpr.ledger.accounting.application.PostingResult;
import io.github.amishpr.ledger.accounting.application.PostingService;
import io.github.amishpr.ledger.accounting.domain.EntryDirection;
import io.github.amishpr.ledger.accounting.domain.LedgerTransaction;
import io.github.amishpr.ledger.accounting.domain.PostingLine;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract of POST /transactions, with the ledger itself mocked out. */
@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PostingService posting;

    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();

    private String body(String extra) {
        return """
                {"description":"Lunch","entries":[
                  {"accountId":"%s","direction":"DEBIT","amountMinor":"1250"},
                  {"accountId":"%s","direction":"CREDIT","amountMinor":"1250"}]%s}
                """.formatted(to, from, extra);
    }

    private PostingResult result(boolean replayed) {
        LedgerTransaction tx = LedgerTransaction.post(
                "Lunch",
                List.of(new PostingLine(to, EntryDirection.DEBIT, 1250), new PostingLine(from, EntryDirection.CREDIT, 1250)),
                "k-1", "a".repeat(64), null, null, Instant.parse("2026-10-01T12:00:00Z"));
        return new PostingResult(tx, replayed, tx.affectedAccountIds());
    }

    @Test
    void passesTheHeaderKeyThroughAndAnswers201WithALocation() throws Exception {
        when(posting.post(any())).thenReturn(result(false));

        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isCreated())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Location", org.hamcrest.Matchers.startsWith("/api/v1/transactions/")))
                .andExpect(jsonPath("$.transaction.entries[0].amountMinor").value("1250"))
                .andExpect(jsonPath("$.affectedAccountIds.length()").value(2));

        ArgumentCaptor<PostTransactionCommand> command = ArgumentCaptor.forClass(PostTransactionCommand.class);
        verify(posting).post(command.capture());
        org.assertj.core.api.Assertions.assertThat(command.getValue().idempotencyKey()).isEqualTo("k-1");
    }

    @Test
    void answers200AndFlagsAReplay() throws Exception {
        when(posting.post(any())).thenReturn(result(true));

        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void refusesAHeaderAndBodyKeyThatDisagree() throws Exception {
        mvc.perform(post("/api/v1/transactions").header("Idempotency-Key", "k-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body(",\"idempotencyKey\":\"k-2\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(posting, never()).post(any());
    }

    @Test
    void validatesTheBodyBeforeTheLedgerSeesIt() throws Exception {
        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"description":"","entries":[{"accountId":"%s","direction":"DEBIT","amountMinor":"5"}]}
                        """.formatted(to)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.violations.length()").value(2));

        verify(posting, never()).post(any());
    }
}
