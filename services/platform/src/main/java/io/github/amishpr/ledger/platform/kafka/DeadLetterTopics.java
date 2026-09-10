package io.github.amishpr.ledger.platform.kafka;

/**
 * Dead letter topics are named per consumer, {@code <topic>.<service>.dlt}, so
 * when two services consume the same topic it is obvious whose records failed.
 */
public final class DeadLetterTopics {

    private DeadLetterTopics() {}

    public static String forTopic(String topic, String consumerName) {
        return topic + "." + consumerName + ".dlt";
    }
}
