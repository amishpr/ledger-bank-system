package io.github.amishpr.ledger.platform.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.amishpr.ledger.events.AccountCreated;
import io.github.amishpr.ledger.events.AccountSnapshot;
import io.github.amishpr.ledger.events.Topics;
import io.github.amishpr.ledger.platform.PlatformTestApplication;
import io.github.amishpr.ledger.platform.json.IntegrationEventCodec;
import io.github.amishpr.ledger.testsupport.EmbeddedPostgresSupport;
import io.github.amishpr.ledger.testsupport.KafkaTopicProbe;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PlatformTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.application.name=platform-test",
            "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            "ledger.kafka.consumer.initial-interval=10ms",
            "ledger.kafka.consumer.max-interval=20ms",
        })
@EmbeddedKafka(partitions = 1, topics = {Topics.ACCOUNTS, DeadLetterIntegrationTest.DLT})
@Import(DeadLetterIntegrationTest.ListenerConfig.class)
class DeadLetterIntegrationTest {

    static final String DLT = Topics.ACCOUNTS + ".platform-test.dlt";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        EmbeddedPostgresSupport.register(registry, "platform_dlt");
    }

    @TestConfiguration
    @Import(ListenerConfig.FlakyListener.class)
    static class ListenerConfig {
        static class FlakyListener {
            final IntegrationEventCodec codec;
            final List<String> handled = new CopyOnWriteArrayList<>();
            final AtomicInteger attempts = new AtomicInteger();

            FlakyListener(IntegrationEventCodec codec) {
                this.codec = codec;
            }

            @KafkaListener(topics = Topics.ACCOUNTS, groupId = "platform-test")
            void on(String json) {
                AccountCreated event = (AccountCreated) codec.read(json);
                if (event.account().name().equals("Flaky") && attempts.incrementAndGet() < 3) {
                    throw new IllegalStateException("database blinked");
                }
                if (event.account().name().equals("Always broken")) {
                    throw new IllegalStateException("this will never work");
                }
                handled.add(event.account().name());
            }
        }
    }

    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired IntegrationEventCodec codec;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired ListenerConfig.FlakyListener listener;

    private String event(String name) {
        return codec.write(AccountCreated.of(
                new AccountSnapshot(UUID.randomUUID(), name, "ASSET", "USD", Instant.now()), Instant.now()));
    }

    @Test
    void retriesTransientFailuresAndDeadLettersPermanentOnes() throws Exception {
        kafka.send(Topics.ACCOUNTS, "a", "{this is not json").get();
        kafka.send(Topics.ACCOUNTS, "b", event("Flaky")).get();
        kafka.send(Topics.ACCOUNTS, "c", event("Always broken")).get();
        kafka.send(Topics.ACCOUNTS, "d", event("Healthy")).get();

        try (KafkaTopicProbe dlt = KafkaTopicProbe.subscribe(broker, DLT)) {
            ConsumerRecord<String, String> poison = dlt.await(r -> r.key().equals("a"), Duration.ofSeconds(20));
            ConsumerRecord<String, String> exhausted = dlt.await(r -> r.key().equals("c"), Duration.ofSeconds(20));

            assertThat(header(poison, "kafka_dlt-exception-cause-fqcn")).startsWith("tools.jackson.");
            assertThat(header(exhausted, "kafka_dlt-original-topic")).isEqualTo(Topics.ACCOUNTS);
        }

        // The flaky record recovered on its third attempt, and the bad records
        // in front of the healthy one did not hold it up.
        long deadline = System.currentTimeMillis() + 10_000;
        while (listener.handled.size() < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(listener.handled).containsExactly("Flaky", "Healthy");
        assertThat(listener.attempts.get()).isEqualTo(3);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? "" : new String(header.value(), StandardCharsets.UTF_8);
    }
}
