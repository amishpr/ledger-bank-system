package io.github.amishpr.ledger.accounting.seed;

/**
 * The mulberry32 generator, ported bit for bit from the TypeScript seed
 * scripts. Java's int arithmetic wraps exactly the way JavaScript's
 * {@code Math.imul} and {@code | 0} do, so the same seed gives the same
 * sequence here, in the original Node seed and in the browser demo.
 */
final class Mulberry32 {

    private int state;

    Mulberry32(int seed) {
        this.state = seed;
    }

    double next() {
        state += 0x6D2B79F5;
        int t = (state ^ (state >>> 15)) * (1 | state);
        t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
        return ((t ^ (t >>> 14)) & 0xFFFF_FFFFL) / 4294967296.0;
    }

    /** Inclusive on both ends, like the seed scripts' {@code randomInt}. */
    int nextInt(int min, int max) {
        return (int) Math.floor(next() * (max - min + 1)) + min;
    }

    <T> T pick(T[] items) {
        return items[nextInt(0, items.length - 1)];
    }
}
