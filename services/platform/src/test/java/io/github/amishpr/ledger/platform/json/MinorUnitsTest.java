package io.github.amishpr.ledger.platform.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class MinorUnitsTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    record Body(MinorUnits amountMinor) {}

    @Test
    void acceptsAStringOfDigits() {
        assertThat(mapper.readValue("{\"amountMinor\":\"1050\"}", Body.class).amountMinor().value()).isEqualTo(1050);
    }

    @Test
    void acceptsAJsonInteger() {
        assertThat(mapper.readValue("{\"amountMinor\":1050}", Body.class).amountMinor().value()).isEqualTo(1050);
    }

    @Test
    void acceptsTheLargestSafeEighteenDigitAmount() {
        assertThat(mapper.readValue("{\"amountMinor\":\"999999999999999999\"}", Body.class).amountMinor().value())
                .isEqualTo(999_999_999_999_999_999L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.5", "\"10.50\"", "-5", "\"-5\"", "\"1e3\"", "\"\"", "\" 10\"", "true", "{}", "\"9999999999999999999\"", "12345678901234567890"})
    void rejectsAnythingThatIsNotWholeCents(String raw) {
        assertThatThrownBy(() -> mapper.readValue("{\"amountMinor\":" + raw + "}", Body.class))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writesBackAsAString() {
        assertThat(mapper.writeValueAsString(new Body(new MinorUnits(1050)))).isEqualTo("{\"amountMinor\":\"1050\"}");
    }
}
