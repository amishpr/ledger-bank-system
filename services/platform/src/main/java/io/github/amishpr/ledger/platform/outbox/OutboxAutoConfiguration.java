package io.github.amishpr.ledger.platform.outbox;

import io.github.amishpr.ledger.platform.PlatformAutoConfiguration;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * The transactional outbox and its relay. Off unless a service sets
 * {@code ledger.outbox.enabled=true}, since it needs the service's schema to
 * include the {@code outbox_event} table.
 */
@AutoConfiguration(
        after = PlatformAutoConfiguration.class,
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
            "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration",
        })
@ConditionalOnClass({JdbcClient.class, KafkaTemplate.class})
@ConditionalOnBooleanProperty("ledger.outbox.enabled")
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Tracer.class)
    static class TracingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        TraceContextCapture traceContextCapture(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
            Tracer t = tracer.getIfAvailable();
            Propagator p = propagator.getIfAvailable();
            return t != null && p != null ? new MicrometerTraceContextCapture(t, p) : TraceContextCapture.none();
        }
    }

    @Bean
    @ConditionalOnMissingBean
    TraceContextCapture noTraceContextCapture() {
        return TraceContextCapture.none();
    }

    @Bean
    @ConditionalOnMissingBean
    Outbox outbox(
            JdbcClient jdbc,
            IntegrationEventCodec codec,
            JsonMapper jsonMapper,
            TraceContextCapture traceContext,
            Clock clock) {
        return new Outbox(jdbc, codec, jsonMapper, traceContext, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(
            JdbcClient jdbc,
            TransactionTemplate transactions,
            KafkaTemplate<String, String> kafka,
            JsonMapper jsonMapper,
            TraceContextCapture traceContext,
            OutboxProperties properties,
            Clock clock) {
        return new OutboxRelay(jdbc, transactions, kafka, jsonMapper, traceContext, properties, clock);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterBinder.class)
    static class MetricsConfiguration {

        /** {@code ledger.outbox.pending} is the number to alert on: it only grows when Kafka is in trouble. */
        @Bean
        MeterBinder outboxMetrics(OutboxRelay relay) {
            return registry -> {
                Gauge.builder("ledger.outbox.pending", relay, OutboxRelay::pendingCount)
                        .description("Events written to the outbox and not yet published to Kafka")
                        .register(registry);
                FunctionCounter.builder("ledger.outbox.published", relay, OutboxRelay::publishedCount)
                        .description("Events this instance has published from the outbox")
                        .register(registry);
            };
        }
    }
}
