package io.github.amishpr.ledger.platform;

import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import io.github.amishpr.ledger.platform.tracing.CurrentTraceId;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/** Beans every service shares, whatever else it runs. */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
public class PlatformAutoConfiguration {

    /**
     * UTC, ticking in whole milliseconds. Postgres keeps microseconds and
     * JavaScript keeps milliseconds, so a timestamp taken at this precision
     * reads back exactly as it was written, everywhere it goes. Things like the
     * scheduler's idempotency keys depend on that.
     */
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.tickMillis(ZoneOffset.UTC);
    }

    @Bean
    @ConditionalOnMissingBean
    IntegrationEventCodec integrationEventCodec(JsonMapper jsonMapper) {
        return new IntegrationEventCodec(jsonMapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CurrentTraceId currentTraceId(ObjectProvider<Tracer> tracers) {
            return CurrentTraceId.from(tracers);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    CurrentTraceId noCurrentTraceId() {
        return CurrentTraceId.none();
    }
}
