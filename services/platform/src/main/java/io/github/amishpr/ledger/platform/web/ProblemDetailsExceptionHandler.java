package io.github.amishpr.ledger.platform.web;

import io.github.amishpr.ledger.platform.tracing.CurrentTraceId;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Turns every error a service can produce into an RFC 9457 Problem Details
 * response ({@code application/problem+json}) with two extensions the
 * dashboard and other clients can rely on:
 *
 * <ul>
 *   <li>{@code code}: a stable error code, such as {@code INSUFFICIENT_FUNDS}
 *   <li>{@code traceId}: the trace this request belongs to, when tracing is on
 * </ul>
 *
 * Validation errors also carry a {@code violations} list. Unexpected errors are
 * logged in full and returned as a generic 500 so no internals leak out.
 */
@RestControllerAdvice
public class ProblemDetailsExceptionHandler extends ResponseEntityExceptionHandler {

    public static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    public static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);

    private final CurrentTraceId currentTraceId;

    public ProblemDetailsExceptionHandler(CurrentTraceId currentTraceId) {
        this.currentTraceId = currentTraceId;
    }

    public record Violation(String field, String message) {}

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> handleApiException(ApiException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        problem.setProperty("code", ex.code());
        return handleExceptionInternal(ex, problem, new HttpHeaders(), ex.status(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled error on {}", describe(request), ex);
        ProblemDetail problem =
                ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side");
        problem.setProperty("code", INTERNAL_ERROR);
        return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            violations.add(new Violation(error.getField(), error.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> violations.add(new Violation(error.getObjectName(), error.getDefaultMessage())));
        return validationProblem(ex, violations, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Violation> violations = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                violations.add(new Violation(name, error.getDefaultMessage()));
            }
        });
        return validationProblem(ex, violations, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return validationProblem(ex, List.of(new Violation(fieldOf(ex), readableReason(ex))), headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = ex.getPropertyName() == null ? "parameter" : ex.getPropertyName();
        String message = "'" + ex.getValue() + "' is not a valid value";
        return validationProblem(ex, List.of(new Violation(field, message)), headers, request);
    }

    /**
     * Every response from the base class, framework errors included, passes
     * through here, so this is the one place that guarantees the extensions.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            Map<String, Object> properties = problem.getProperties();
            if (properties == null || !properties.containsKey("code")) {
                problem.setProperty("code", defaultCode(statusCode));
            }
            if (problem.getInstance() == null && request instanceof ServletWebRequest servlet) {
                problem.setInstance(URI.create(servlet.getRequest().getRequestURI()));
            }
            String traceId = currentTraceId.get();
            if (traceId != null) {
                problem.setProperty("traceId", traceId);
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationProblem(
            Exception ex, List<Violation> violations, HttpHeaders headers, WebRequest request) {
        String detail = violations.stream()
                .map(v -> v.field() + ": " + v.message())
                .collect(Collectors.joining("; "));
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setTitle("Invalid request");
        problem.setProperty("code", VALIDATION_ERROR);
        problem.setProperty("violations", violations);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    private static String fieldOf(HttpMessageNotReadableException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
                StringBuilder field = new StringBuilder();
                for (JacksonException.Reference ref : jackson.getPath()) {
                    if (ref.getPropertyName() != null) {
                        field.append(field.isEmpty() ? "" : ".").append(ref.getPropertyName());
                    } else if (ref.getIndex() >= 0) {
                        field.append('[').append(ref.getIndex()).append(']');
                    }
                }
                return field.isEmpty() ? "body" : field.toString();
            }
        }
        return "body";
    }

    private static String readableReason(HttpMessageNotReadableException ex) {
        // A value type such as MinorUnits rejects bad input with an
        // IllegalArgumentException that already explains the rule.
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof IllegalArgumentException) {
            return cause.getMessage();
        }
        for (Throwable c = ex.getCause(); c != null; c = c.getCause()) {
            if (c instanceof JacksonException jackson && !jackson.getPath().isEmpty()) {
                return "has an invalid value or the wrong type";
            }
        }
        return "the request body is missing or is not valid JSON";
    }

    private static String defaultCode(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        if (resolved == null) {
            return "HTTP_" + status.value();
        }
        return switch (resolved) {
            case INTERNAL_SERVER_ERROR -> INTERNAL_ERROR;
            case BAD_REQUEST -> VALIDATION_ERROR;
            default -> resolved.name();
        };
    }

    private static String describe(WebRequest request) {
        return request instanceof ServletWebRequest servlet
                ? servlet.getRequest().getMethod() + " " + servlet.getRequest().getRequestURI()
                : request.getDescription(false);
    }
}
