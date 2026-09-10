package io.github.amishpr.ledger.platform.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Time ordered UUIDs (version 7, RFC 9562). The first 48 bits are the Unix
 * time in milliseconds, so ids sort in creation order. That keeps B-tree
 * index inserts at the right-hand edge instead of scattered across the index,
 * and gives a stable order to rows created in the same millisecond.
 *
 * <p>Within one millisecond a 12 bit counter (the RFC's "method 1") keeps ids
 * from this JVM strictly increasing. If more than 4096 ids are asked for in one
 * millisecond, the timestamp is borrowed from the next millisecond rather than
 * breaking the ordering.
 */
public final class Uuids {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static long lastMillis = -1;
    private static int counter;

    private Uuids() {}

    public static UUID v7() {
        long millis;
        int sequence;
        synchronized (Uuids.class) {
            long now = System.currentTimeMillis();
            if (now > lastMillis) {
                lastMillis = now;
                counter = RANDOM.nextInt(1 << 10); // leave headroom for increments
            } else if (++counter > 0xFFF) {
                lastMillis++;
                counter = 0;
            }
            millis = lastMillis;
            sequence = counter;
        }
        long mostSignificant = (millis << 16) | (0x7L << 12) | sequence;
        long leastSignificant = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(mostSignificant, leastSignificant);
    }
}
