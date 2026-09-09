package io.github.amishpr.ledger.testsupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * Reads what was published to a topic during a test, from the beginning of the
 * topic, in its own consumer group so it never steals records from the
 * application's own listeners.
 */
public final class KafkaTopicProbe implements AutoCloseable {

    private final Consumer<String, String> consumer;
    private final List<ConsumerRecord<String, String>> seen = new ArrayList<>();

    private KafkaTopicProbe(Consumer<String, String> consumer) {
        this.consumer = consumer;
    }

    public static KafkaTopicProbe subscribe(EmbeddedKafkaBroker broker, String... topics) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "probe-" + UUID.randomUUID(), false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                        .createConsumer();
        broker.consumeFromEmbeddedTopics(consumer, topics);
        return new KafkaTopicProbe(consumer);
    }

    /** Waits until a record matching {@code match} arrives, and returns it. */
    public ConsumerRecord<String, String> await(Predicate<ConsumerRecord<String, String>> match, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : seen) {
                if (match.test(record)) {
                    return record;
                }
            }
            consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
        }
        throw new AssertionError("No matching record arrived within " + timeout + ". Saw: " + seen);
    }

    /** Everything that arrives within {@code window}. */
    public List<ConsumerRecord<String, String>> collect(Duration window) {
        Instant deadline = Instant.now().plus(window);
        while (Instant.now().isBefore(deadline)) {
            consumer.poll(Duration.ofMillis(200)).forEach(seen::add);
        }
        return List.copyOf(seen);
    }

    @Override
    public void close() {
        consumer.close();
    }
}
