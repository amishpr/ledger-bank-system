package io.github.amishpr.ledger.accounting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountTypeTest {

    @ParameterizedTest(name = "{1} on {0} changes the balance by {2}")
    @CsvSource({
        "ASSET,     DEBIT,   100",
        "ASSET,     CREDIT, -100",
        "EXPENSE,   DEBIT,   100",
        "EXPENSE,   CREDIT, -100",
        "LIABILITY, DEBIT,  -100",
        "LIABILITY, CREDIT,  100",
        "EQUITY,    DEBIT,  -100",
        "EQUITY,    CREDIT,  100",
        "REVENUE,   DEBIT,  -100",
        "REVENUE,   CREDIT,  100",
    })
    void followsTheDebitNormalRule(AccountType type, EntryDirection direction, long expected) {
        assertThat(type.signedDelta(direction, 100)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"ASSET, 500, 500", "EXPENSE, 500, 500", "REVENUE, 500, -500", "EQUITY, -250, 250"})
    void turnsNetDebitsIntoABalance(AccountType type, long netDebits, long balance) {
        assertThat(type.balanceFromNetDebits(netDebits)).isEqualTo(balance);
    }

    @ParameterizedTest
    @CsvSource({"ASSET, true", "LIABILITY, false", "EQUITY, false", "REVENUE, false", "EXPENSE, false"})
    void onlyProtectsSpendableAccountsFromOverdraft(AccountType type, boolean protectedFromOverdraft) {
        assertThat(type.isOverdraftProtected()).isEqualTo(protectedFromOverdraft);
    }
}
