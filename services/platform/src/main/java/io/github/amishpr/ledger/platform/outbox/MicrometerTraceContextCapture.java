package io.github.amishpr.ledger.platform.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;

/** {@link TraceContextCapture} backed by Micrometer Tracing. */
public class MicrometerTraceContextCapture implements TraceContextCapture {

    private final Tracer tracer;
    private final Propagator propagator;

    public MicrometerTraceContextCapture(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    @Override
    public Map<String, String> capture() {
        Span current = tracer.currentSpan();
        if (current == null) {
            return Map.of();
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(current.context(), carrier, Map::put);
        return carrier;
    }

    @Override
    public void runWithin(Map<String, String> captured, String spanName, Runnable action) {
        if (captured == null || captured.isEmpty()) {
            action.run();
            return;
        }
        Span span = propagator.extract(captured, Map::get).name(spanName).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            action.run();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
