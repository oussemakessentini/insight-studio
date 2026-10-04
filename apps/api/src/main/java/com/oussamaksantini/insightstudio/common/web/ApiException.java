package com.oussamaksantini.insightstudio.common.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * An error whose message is safe to show to API clients. Subclasses may add properties to the
 * problem detail ({@link #getProperties()}), e.g. the {@code code} of a plan limit refusal.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String detail) {
        super(detail);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }

    /** Extra members of the problem detail (plain values only); none by default. */
    public Map<String, Object> getProperties() {
        return Map.of();
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, detail);
    }

    public static ApiException notFound(String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, detail);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(HttpStatus.UNAUTHORIZED, detail);
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, detail);
    }

    public static ApiException conflict(String detail) {
        return new ApiException(HttpStatus.CONFLICT, detail);
    }
}
