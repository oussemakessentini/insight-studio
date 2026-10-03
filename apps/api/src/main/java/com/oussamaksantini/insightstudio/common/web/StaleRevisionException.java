package com.oussamaksantini.insightstudio.common.web;

import java.time.Instant;
import org.springframework.http.HttpStatus;

/**
 * A 409 for a save based on an outdated revision: rendered as a problem detail that also carries
 * {@code currentRevision}, {@code updatedBy} and {@code updatedAt}, so the client can say who saved
 * in between and offer to reload.
 *
 * @see ApiExceptionHandler
 */
public class StaleRevisionException extends ApiException {

    private final int currentRevision;
    private final String updatedBy;
    private final Instant updatedAt;

    public StaleRevisionException(String detail, int currentRevision, String updatedBy, Instant updatedAt) {
        super(HttpStatus.CONFLICT, detail);
        this.currentRevision = currentRevision;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    public int getCurrentRevision() {
        return currentRevision;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
