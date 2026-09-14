package io.github.amishpr.ledger.accounting.application;

import static io.github.amishpr.ledger.accounting.domain.EntryDirection.CREDIT;
import static io.github.amishpr.ledger.accounting.domain.EntryDirection.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.amishpr.ledger.accounting.domain.PostingLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestHasherTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void ignoresTheOrderEntriesWereSentIn() {
        String first = RequestHasher.hash("Lunch", List.of(new PostingLine(a, DEBIT, 100), new PostingLine(b, CREDIT, 100)));
        String second = RequestHasher.hash("Lunch", List.of(new PostingLine(b, CREDIT, 100), new PostingLine(a, DEBIT, 100)));

        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void changesWhenTheAmountOrDescriptionChanges() {
        String original = RequestHasher.hash("Lunch", List.of(new PostingLine(a, DEBIT, 100), new PostingLine(b, CREDIT, 100)));

        assertThat(RequestHasher.hash("Lunch", List.of(new PostingLine(a, DEBIT, 200), new PostingLine(b, CREDIT, 200))))
                .isNotEqualTo(original);
        assertThat(RequestHasher.hash("Dinner", List.of(new PostingLine(a, DEBIT, 100), new PostingLine(b, CREDIT, 100))))
                .isNotEqualTo(original);
    }

    @Test
    void cannotBeFooledByMovingTextBetweenFields() {
        // Length-prefixing the description keeps "ab" + "c..." from colliding with "a" + "bc...".
        String one = RequestHasher.hash("x|" + a + ",DEBIT,1", List.of(new PostingLine(b, CREDIT, 1)));
        String two = RequestHasher.hash("x", List.of(new PostingLine(a, DEBIT, 1), new PostingLine(b, CREDIT, 1)));

        assertThat(one).isNotEqualTo(two);
    }
}
