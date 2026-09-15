package io.github.amishpr.ledger.accounting.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.amishpr.ledger.accounting.IntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The cases from the original server/src/ledger/ledgerService.test.ts, run
 * end to end over HTTP against real Postgres, plus the ones the new API adds.
 */
class LedgerApiIntegrationTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;

    private String open(String name, String type) throws Exception {
        String body = mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"type\":\"" + type + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/accounts/")))
                .andExpect(jsonPath("$.balanceMinor").value("0"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asString();
    }

    private ResultActions transfer(String description, String debit, String credit, long amount, String key) throws Exception {
        var request = post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content("""
                {"description":"%s","entries":[
                  {"accountId":"%s","direction":"DEBIT","amountMinor":"%d"},
                  {"accountId":"%s","direction":"CREDIT","amountMinor":"%d"}]}
                """.formatted(description, debit, amount, credit, amount));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request);
    }

    private String balanceOf(String accountId) throws Exception {
        String body = mvc.perform(get("/api/v1/accounts/" + accountId)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("balanceMinor").asString();
    }

    private String transactionId(ResultActions result) throws Exception {
        JsonNode body = json.readTree(result.andReturn().getResponse().getContentAsString());
        return body.get("transaction").get("id").asString();
    }

    @Test
    void rejectsATransactionWhereDebitsAndCreditsDoNotBalance() throws Exception {
        String asset = open("Checking", "ASSET");
        String equity = open("Equity", "EQUITY");

        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"description":"unbalanced","entries":[
                          {"accountId":"%s","direction":"DEBIT","amountMinor":"1000"},
                          {"accountId":"%s","direction":"CREDIT","amountMinor":"900"}]}
                        """.formatted(asset, equity)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNBALANCED_TRANSACTION"));
    }

    @Test
    void refusesToTakeAnAssetAccountNegativeAndLeavesTheBalanceAlone() throws Exception {
        String asset = open("Checking", "ASSET");
        String equity = open("Equity", "EQUITY");

        transfer("overdraft attempt", equity, asset, 500, null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));

        assertThat(balanceOf(asset)).isEqualTo("0");
    }

    @Test
    void replaysAnIdempotentRequestInsteadOfPostingTwice() throws Exception {
        String asset = open("Checking", "ASSET");
        String equity = open("Equity", "EQUITY");

        String first = transactionId(transfer("opening balance", asset, equity, 1000, "seed-key-1")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false)));
        String second = transactionId(transfer("opening balance", asset, equity, 1000, "seed-key-1")
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.replayed").value(true)));

        assertThat(second).isEqualTo(first);
        assertThat(balanceOf(asset)).isEqualTo("1000");
    }

    @Test
    void acceptsTheIdempotencyKeyInTheBodyLikeTheFirstApiDid() throws Exception {
        String asset = open("Checking", "ASSET");
        String equity = open("Equity", "EQUITY");
        String body = """
                {"description":"opening balance","idempotencyKey":"body-key","entries":[
                  {"accountId":"%s","direction":"DEBIT","amountMinor":1000},
                  {"accountId":"%s","direction":"CREDIT","amountMinor":1000}]}
                """.formatted(asset, equity);

        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.idempotencyKey").value("body-key"));
    }

    @Test
    void rejectsReusingAKeyWithADifferentPayload() throws Exception {
        String asset = open("Checking", "ASSET");
        String equity = open("Equity", "EQUITY");

        transfer("first", asset, equity, 1000, "reused-key").andExpect(status().isCreated());
        transfer("different payload, same key", asset, equity, 2000, "reused-key")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void reversalRestoresThePriorBalanceAndVoidsTheOriginal() throws Exception {
        String asset = open("Checking", "ASSET");
        String equity = open("Equity", "EQUITY");
        transfer("opening balance", asset, equity, 5000, null).andExpect(status().isCreated());
        String spend = transactionId(transfer("spend", equity, asset, 1200, null));
        assertThat(balanceOf(asset)).isEqualTo("3800");

        mvc.perform(post("/api/v1/transactions/" + spend + "/reverse"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transaction.status").value("POSTED"))
                .andExpect(jsonPath("$.transaction.reversalOf").value(spend))
                .andExpect(jsonPath("$.transaction.description").value("Reversal of " + spend + ": spend"));

        assertThat(balanceOf(asset)).isEqualTo("5000");
        mvc.perform(get("/api/v1/transactions/" + spend)).andExpect(jsonPath("$.status").value("VOIDED"));
    }

    @Test
    void refusesToReverseAnAlreadyVoidedTransaction() throws Exception {
        String asset = open("Checking", "ASSET");
        String equity = open("Equity", "EQUITY");
        String tx = transactionId(transfer("opening balance", asset, equity, 1000, null));

        mvc.perform(post("/api/v1/transactions/" + tx + "/reverse").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Typo\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transaction.description").value("Typo"));
        mvc.perform(post("/api/v1/transactions/" + tx + "/reverse"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_VOIDED"));
    }

    @Test
    void returns404ForAnUnknownAccountOrTransaction() throws Exception {
        String asset = open("Checking", "ASSET");

        transfer("to nowhere", asset, UUID.randomUUID().toString(), 100, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mvc.perform(post("/api/v1/transactions/" + UUID.randomUUID() + "/reverse"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    @Test
    void refusesToMixCurrenciesInOneTransaction() throws Exception {
        String dollars = open("Checking", "ASSET");
        String euros = mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Euro float\",\"type\":\"EQUITY\",\"currency\":\"EUR\"}"))
                .andReturn().getResponse().getContentAsString();

        transfer("fx", dollars, json.readTree(euros).get("id").asString(), 100, null)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    @Test
    void rejectsFractionalCentsAtTheEdge() throws Exception {
        String asset = open("Checking", "ASSET");
        String equity = open("Equity", "EQUITY");

        mvc.perform(post("/api/v1/transactions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"description":"half a cent","entries":[
                          {"accountId":"%s","direction":"DEBIT","amountMinor":10.5},
                          {"accountId":"%s","direction":"CREDIT","amountMinor":10.5}]}
                        """.formatted(asset, equity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.violations[0].field").value("entries[0].amountMinor"));
    }

    @Test
    void listsAccountsWithBalancesAndStatementsWithRunningBalances() throws Exception {
        String checking = open("Checking - Alex", "ASSET");
        String equity = open("Equity", "EQUITY");
        String groceries = open("Expenses - Groceries", "EXPENSE");
        transfer("Opening balance", checking, equity, 10_000, null);
        transfer("Groceries", groceries, checking, 2_550, null);
        transfer("More groceries", groceries, checking, 450, null);

        mvc.perform(get("/api/v1/accounts"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].name").value("Checking - Alex"))
                .andExpect(jsonPath("$[0].balanceMinor").value("7000"))
                .andExpect(jsonPath("$[1].balanceMinor").value("10000"))
                .andExpect(jsonPath("$[2].balanceMinor").value("3000"));

        mvc.perform(get("/api/v1/accounts/" + checking + "/statement").param("limit", "2"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].description").value("More groceries"))
                .andExpect(jsonPath("$[0].direction").value("CREDIT"))
                .andExpect(jsonPath("$[0].amountMinor").value("450"))
                .andExpect(jsonPath("$[0].runningBalanceMinor").value("7000"))
                .andExpect(jsonPath("$[1].runningBalanceMinor").value("7450"));

        mvc.perform(get("/api/v1/accounts/" + checking + "/statement").param("limit", "5000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void exportsTheWholeStatementAsCsv() throws Exception {
        String checking = open("Checking - Alex", "ASSET");
        String equity = open("Equity", "EQUITY");
        transfer("Opening balance", checking, equity, 10_000, null);
        transfer("=cmd|' /C calc'!A0", equity, checking, 50, null);

        String csv = mvc.perform(get("/api/v1/accounts/" + checking + "/statement/export"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(header().string("Content-Disposition", containsString("checking-alex-statement.csv")))
                .andReturn().getResponse().getContentAsString();

        String[] lines = csv.split("\r\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).contains("Opening balance,DEBIT,100.00,100.00");
        assertThat(lines[2]).contains(",'=cmd|' /C calc'!A0,CREDIT,0.50,99.50");
    }
}
