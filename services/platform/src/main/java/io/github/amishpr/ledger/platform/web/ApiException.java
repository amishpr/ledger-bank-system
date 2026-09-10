package io.github.amishpr.ledger.platform.web;

import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * A failure the caller is meant to see, with the HTTP status it maps to and a
 * stable machine readable code such as {@code INSUFFICIENT_FUNDS}. The code is
 * the contract. The message is for people and may change.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = Objects.requireNonNull(status);
        this.code = Objects.requireNonNull(code);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
