package io.github.amishpr.ledger.accounting.domain;

import static io.github.amishpr.ledger.accounting.domain.EntryDirection.CREDIT;
import static io.github.amishpr.ledger.accounting.domain.EntryDirection.DEBIT;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PostingRulesTest {

    private final UUID checking = UUID.randomUUID();
    private final UUID groceries = UUID.randomUUID();
    private final UUID dining = UUID.randomUUID();

    @Test
    void acceptsABalancedTransaction() {
        assertThatCode(() -> PostingRules.validate(List.of(
                        new PostingLine(groceries, DEBIT, 1_000),
                        new PostingLine(checking, CREDIT, 1_000))))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsASplitAcrossMoreThanTwoAccounts() {
        assertThatCode(() -> PostingRules.validate(List.of(
                        new PostingLine(groceries, DEBIT, 700),
                        new PostingLine(dining, DEBIT, 300),
                        new PostingLine(checking, CREDIT, 1_000))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsATransactionThatDoesNotBalance() {
        assertThatThrownBy(() -> PostingRules.validate(List.of(
                        new PostingLine(checking, DEBIT, 1_000),
                        new PostingLine(groceries, CREDIT, 900))))
                .isInstanceOfSatisfying(LedgerException.class, e -> org.assertj.core.api.Assertions
                        .assertThat(e.code()).isEqualTo("UNBALANCED_TRANSACTION"));
    }

    @Test
    void rejectsASingleEntry() {
        assertThatThrownBy(() -> PostingRules.validate(List.of(new PostingLine(checking, DEBIT, 1_000))))
                .hasMessageContaining("at least two entries");
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> PostingRules.validate(List.of(
                        new PostingLine(checking, DEBIT, 0),
                        new PostingLine(groceries, CREDIT, 0))))
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> PostingRules.validate(List.of(
                        new PostingLine(checking, DEBIT, -5),
                        new PostingLine(groceries, CREDIT, -5))))
                .hasMessageContaining("positive");
    }

    @Test
    void rejectsTotalsThatWouldOverflowInsteadOfWrappingAround() {
        assertThatThrownBy(() -> PostingRules.validate(List.of(
                        new PostingLine(checking, DEBIT, Long.MAX_VALUE),
                        new PostingLine(dining, DEBIT, Long.MAX_VALUE),
                        new PostingLine(groceries, CREDIT, 2))))
                .isInstanceOf(ArithmeticException.class);
    }
}
