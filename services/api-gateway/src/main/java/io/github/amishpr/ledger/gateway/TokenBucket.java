package io.github.amishpr.ledger.gateway;

/**
 * A classic token bucket: up to {@code capacity} tokens, refilled at a steady
 * rate, one spent per request. Synchronized because one client can have
 * several requests in flight at once.
 */
final class TokenBucket {

    private final int capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefill;

    TokenBucket(int capacity, int refillPerSecond, long nowNanos) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefill = nowNanos;
    }

    synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        if (tokens >= 1) {
            tokens -= 1;
            return true;
        }
        return false;
    }

    synchronized int remaining(long nowNanos) {
        refill(nowNanos);
        return (int) Math.floor(tokens);
    }

    private void refill(long nowNanos) {
        long elapsed = Math.max(0, nowNanos - lastRefill);
        tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
        lastRefill = nowNanos;
    }
}
