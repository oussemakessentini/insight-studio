package com.oussamaksantini.insightstudio.common.web;

import org.springframework.http.HttpStatus;

/** A 429 answer; {@code retryAfterSeconds} is sent as the {@code Retry-After} header. */
public class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String detail, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, detail);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
