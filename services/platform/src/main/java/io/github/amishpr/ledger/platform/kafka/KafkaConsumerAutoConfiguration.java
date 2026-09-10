package io.github.amishpr.ledger.platform.kafka;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import tools.jackson.core.JacksonException;

/**
 * One retry policy for every Kafka consumer in the system: a few retries with
 * exponential backoff for transient failures, then the record goes to that
 * consumer's dead letter topic with the exception recorded in its headers, so
 * one bad record never blocks the partition behind it. A record that is not
 * valid JSON, or is an event type nobody knows, skips the retries entirely,
 * because retrying cannot fix it.
 */
@AutoConfiguration(beforeName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(DefaultErrorHandler.class)
@EnableConfigurationProperties(DeadLetterProperties.class)
public class KafkaConsumerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    DefaultErrorHandler kafkaErrorHandler(
            KafkaOperations<?, ?> kafka, DeadLetterProperties properties, Environment environment) {
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(properties.maxRetries());
        backOff.setInitialInterval(properties.initialInterval().toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(properties.maxInterval().toMillis());

        DefaultErrorHandler handler;
        if (properties.deadLetterEnabled()) {
            String consumer = environment.getProperty("spring.application.name", "consumer");
            DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                    kafka,
                    // A negative partition lets Kafka pick one, so the dead
                    // letter topic does not need as many partitions as the source.
                    (record, exception) -> new TopicPartition(DeadLetterTopics.forTopic(record.topic(), consumer), -1));
            handler = new DefaultErrorHandler(recoverer, backOff);
        } else {
            handler = new DefaultErrorHandler(
                    (record, exception) -> log.warn(
                            "Dropping record {}-{}@{} after retries: {}",
                            record.topic(), record.partition(), record.offset(), exception.getMessage()),
                    backOff);
        }
        handler.addNotRetryableExceptions(JacksonException.class);
        return handler;
    }
}
