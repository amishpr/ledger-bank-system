package io.github.amishpr.ledger.platform.outbox;

import java.util.Map;

/**
 * Captures the current trace context (W3C {@code traceparent} and friends) when
 * an event is written, and restores it when the relay publishes the event
 * later on another thread. Without this the trace would stop at the database
 * and the consumer's work would show up in Jaeger as an unrelated trace.
 */
public interface TraceContextCapture {

    Map<String, String> capture();

    /** Runs {@code action} as a child of the captured context, or plainly if there is none. */
    void runWithin(Map<String, String> captured, String spanName, Runnable action);

    static TraceContextCapture none() {
        return new TraceContextCapture() {
            @Override
            public Map<String, String> capture() {
                return Map.of();
            }

            @Override
            public void runWithin(Map<String, String> captured, String spanName, Runnable action) {
                action.run();
            }
        };
    }
}
