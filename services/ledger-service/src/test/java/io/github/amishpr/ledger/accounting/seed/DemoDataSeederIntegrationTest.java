package io.github.amishpr.ledger.accounting.seed;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amishpr.ledger.accounting.IntegrationTest;
import io.github.amishpr.ledger.accounting.application.AccountService;
import io.github.amishpr.ledger.accounting.application.AccountService.AccountWithBalance;
import io.github.amishpr.ledger.accounting.application.PostingService;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class DemoDataSeederIntegrationTest extends IntegrationTest {

    @Autowired AccountService accounts;
    @Autowired PostingService posting;
    @Autowired Clock clock;

    @Test
    void buildsAYearOfHistoryThatNeverOverdrawsCheckingAndOnlyRunsOnce() throws Exception {
        DemoDataSeeder seeder = new DemoDataSeeder(accounts, posting, clock, "America/New_York");

        seeder.run(null);

        Map<String, Long> balances = accounts.list().stream()
                .collect(Collectors.toMap(a -> a.account().getName(), AccountWithBalance::balanceMinor));
        assertThat(balances).hasSize(12).containsKeys("Checking - Alex", "Savings - Alex", "Expenses - Shopping");

        // The books balance: debit-normal balances equal credit-normal ones.
        long assetsAndExpenses = accounts.list().stream()
                .filter(a -> a.account().getType().isDebitNormal())
                .mapToLong(AccountWithBalance::balanceMinor).sum();
        long everythingElse = accounts.list().stream()
                .filter(a -> !a.account().getType().isDebitNormal())
                .mapToLong(AccountWithBalance::balanceMinor).sum();
        assertThat(assetsAndExpenses).isEqualTo(everythingElse);

        Long lowestCheckingBalance = jdbc.queryForObject("""
                SELECT MIN(running) FROM (
                    SELECT SUM(CASE WHEN e.direction = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)
                           OVER (ORDER BY e.created_at, e.seq) AS running
                    FROM journal_entry e JOIN account a ON a.id = e.account_id
                    WHERE a.name = 'Checking - Alex') r
                """, Long.class);
        assertThat(lowestCheckingBalance).isNotNegative();

        Long months = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT date_trunc('month', created_at)) FROM ledger_transaction", Long.class);
        assertThat(months).isGreaterThanOrEqualTo(12);
        List<String> voided = jdbc.queryForList("SELECT description FROM ledger_transaction WHERE status = 'VOIDED'", String.class);
        assertThat(voided).containsExactly("Interest earned");

        long transactions = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Long.class);
        seeder.run(null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transaction", Long.class)).isEqualTo(transactions);
    }
}
