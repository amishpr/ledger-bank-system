package io.github.amishpr.ledger.platform.json;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigInteger;
import java.util.regex.Pattern;

/**
 * An amount of money in whole minor units (cents), as it arrives in a request.
 *
 * <p>It accepts a JSON integer ({@code 1050}) or a string of digits
 * ({@code "1050"}) and nothing else. A JavaScript client should always send
 * the string, since a JS number cannot represent every cent above 2^53.
 * Decimals, signs, exponents and blanks are rejected rather than rounded, so a
 * float can never quietly become money. Zero is let through here and refused
 * by the business rules, which give it a more specific error.
 */
@Schema(type = "string", pattern = "^\\d+$", example = "1050",
        description = "Whole cents as a string of digits. \"1050\" is $10.50")
public record MinorUnits(long value) {

    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");
    private static final String RULE = "must be a whole number of cents written as digits, for example \"1050\"";

    public MinorUnits {
        if (value < 0) {
            throw new IllegalArgumentException("Amount " + RULE);
        }
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static MinorUnits parse(Object raw) {
        return switch (raw) {
            case Integer i -> new MinorUnits(i);
            case Long l -> new MinorUnits(l);
            case BigInteger big -> throw new IllegalArgumentException("Amount is too large");
            case String s when DIGITS.matcher(s).matches() -> new MinorUnits(Long.parseLong(s));
            case null -> throw new IllegalArgumentException("Amount is required");
            default -> throw new IllegalArgumentException("Amount " + RULE);
        };
    }

    @JsonValue
    public String toJson() {
        return Long.toString(value);
    }
}
