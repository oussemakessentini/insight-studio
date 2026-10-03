package com.oussamaksantini.insightstudio.common.web;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every API error as an RFC 9457 problem detail
 * ({@code application/problem+json}) with a human-readable {@code detail}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TooManyRequestsException.class)
    ResponseEntity<ProblemDetail> handleTooManyRequests(TooManyRequestsException ex) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage()));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    ResponseEntity<ProblemDetail> handleServiceUnavailable(ServiceUnavailableException ex) {
        return ResponseEntity.status(ex.getStatus())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.getRetryAfterSeconds()))
                .body(ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage()));
    }

    /** Invalid fields of a JSON body checked by the service: {@code errors: [{field, message}]}. */
    @ExceptionHandler(FieldErrorsException.class)
    ProblemDetail handleFieldErrors(FieldErrorsException ex) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        body.setProperty("errors", ex.getErrors());
        return body;
    }

    /** A save based on an outdated revision: who saved since, and which revision is current. */
    @ExceptionHandler(StaleRevisionException.class)
    ProblemDetail handleStaleRevision(StaleRevisionException ex) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
        body.setProperty("currentRevision", ex.getCurrentRevision());
        body.setProperty("updatedBy", ex.getUpdatedBy());
        body.setProperty("updatedAt", ex.getUpdatedAt());
        return body;
    }

    @ExceptionHandler(ApiException.class)
    ProblemDetail handleApiException(ApiException ex) {
        return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled API error", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String detail = "Invalid value '%s' for parameter '%s'.".formatted(ex.getValue(), ex.getPropertyName());
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> "'%s' %s".formatted(
                                result.getMethodParameter().getParameterName(), error.getDefaultMessage())))
                .toList();
        return validationProblem(ex, errors, headers, request);
    }

    /** Invalid JSON request bodies ({@code @Valid @RequestBody}): one message per field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> errors = ex.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError field
                        ? "'%s' %s".formatted(field.getField(), field.getDefaultMessage())
                        : error.getDefaultMessage())
                .sorted()
                .toList();
        return validationProblem(ex, errors, headers, request);
    }

    private ResponseEntity<Object> validationProblem(
            Exception ex, List<String> errors, HttpHeaders headers, WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, String.join("; ", errors) + ".");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }
}
