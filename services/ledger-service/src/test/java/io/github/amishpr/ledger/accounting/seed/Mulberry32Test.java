package io.github.amishpr.ledger.accounting.seed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Mulberry32Test {

    /** Produced by the TypeScript generator in web/src/demo/seed.ts with the same seed. */
    @Test
    void matchesTheTypeScriptGeneratorValueForValue() {
        Mulberry32 random = new Mulberry32(1337);

        assertThat(random.next()).isEqualTo(0.1844118325971067);
        assertThat(random.next()).isEqualTo(0.18998925131745636);
        assertThat(random.next()).isEqualTo(0.8104719922412187);
        assertThat(random.next()).isEqualTo(0.6437488221563399);
        assertThat(random.next()).isEqualTo(0.430774615611881);
    }

    @Test
    void drawsTheSameIntegersAsRandomInt() {
        Mulberry32 random = new Mulberry32(1337);
        int[] drawn = new int[8];
        for (int i = 0; i < drawn.length; i++) {
            drawn[i] = random.nextInt(0, 59);
        }

        assertThat(drawn).containsExactly(11, 11, 48, 38, 25, 22, 31, 32);
    }
}
