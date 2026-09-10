package io.github.amishpr.ledger.platform.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UuidsTest {

    @Test
    void isAVersion7Uuid() {
        UUID id = Uuids.v7();

        assertThat(id.version()).isEqualTo(7);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    void carriesTheCurrentTimeInItsFirst48Bits() {
        long before = System.currentTimeMillis();
        UUID id = Uuids.v7();
        long after = System.currentTimeMillis();

        long embedded = id.getMostSignificantBits() >>> 16;
        assertThat(embedded).isBetween(before, after + 1);
    }

    @Test
    void sortsInCreationOrderEvenWithinOneMillisecond() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            ids.add(Uuids.v7());
        }

        // UUID.compareTo compares signed longs, so compare the canonical
        // strings, which is also how Postgres orders a uuid column.
        List<String> asText = ids.stream().map(UUID::toString).toList();
        assertThat(asText).isSorted().doesNotHaveDuplicates();
    }
}
