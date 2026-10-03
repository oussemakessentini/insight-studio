package com.oussamaksantini.insightstudio.common.web;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * A 400 for a JSON body with one or more invalid fields: rendered as a problem detail whose
 * {@code errors} property lists {@code {field, message}} (field = JSON path such as {@code metrics} or
 * {@code range.from}) and whose {@code detail} joins the messages.
 */
public class FieldErrorsException extends ApiException {

    /** One invalid field. */
    public record FieldError(String field, String message) {
    }

    private final List<FieldError> errors;

    public FieldErrorsException(List<FieldError> errors) {
        super(HttpStatus.BAD_REQUEST, detail(errors));
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> getErrors() {
        return errors;
    }

    private static String detail(List<FieldError> errors) {
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("A FieldErrorsException needs at least one error");
        }
        return String.join(" ", errors.stream().map(FieldError::message).distinct().toList());
    }
}
