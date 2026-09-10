package io.github.amishpr.ledger.platform.json;

import io.github.amishpr.ledger.events.IntegrationEvent;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes {@link IntegrationEvent}s with the application's own
 * {@link JsonMapper}. Kafka records carry plain JSON strings, so serialization
 * is visible and testable here rather than hidden in a Kafka serializer.
 */
public class IntegrationEventCodec {

    private final JsonMapper jsonMapper;

    public IntegrationEventCodec(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public String write(IntegrationEvent event) {
        return jsonMapper.writeValueAsString(event);
    }

    /** Throws a {@code JacksonException} for a malformed or unknown event, which consumers treat as permanent. */
    public IntegrationEvent read(String json) {
        return jsonMapper.readValue(json, IntegrationEvent.class);
    }
}
