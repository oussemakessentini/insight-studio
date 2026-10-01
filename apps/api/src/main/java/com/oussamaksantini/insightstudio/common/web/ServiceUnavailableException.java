package com.oussamaksantini.insightstudio.common.web;

import org.springframework.http.HttpStatus;

/** A 503 answer; {@code retryAfterSeconds} is sent as the {@code Retry-After} header. */
public class ServiceUnavailableException extends ApiException {

    private final long retryAfterSeconds;

    public ServiceUnavailableException(String detail, long retryAfterSeconds) {
        super(HttpStatus.SERVICE_UNAVAILABLE, detail);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
