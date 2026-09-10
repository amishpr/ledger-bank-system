package io.github.amishpr.ledger.platform.tracing;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The id of the trace the current thread is working on, or null when tracing
 * is off. Error responses and log lines carry it so a support ticket can be
 * matched to the exact request in Jaeger.
 */
@FunctionalInterface
public interface CurrentTraceId {

    String get();

    static CurrentTraceId none() {
        return () -> null;
    }

    /** Only referenced when Micrometer Tracing is on the classpath. */
    static CurrentTraceId from(ObjectProvider<Tracer> tracers) {
        return () -> {
            Tracer tracer = tracers.getIfAvailable();
            Span span = tracer == null ? null : tracer.currentSpan();
            return span == null ? null : span.context().traceId();
        };
    }
}
