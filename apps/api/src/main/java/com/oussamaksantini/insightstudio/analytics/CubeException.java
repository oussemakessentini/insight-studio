package com.oussamaksantini.insightstudio.analytics;

/**
 * Cube could not answer a {@code /load} call: unreachable, timed out, an HTTP error, an
 * {@code error} in the body or a malformed response. The details have already been logged by
 * {@link CubeClient}; the message is generic and must not be shown to API clients either way.
 */
public class CubeException extends RuntimeException {

    private final boolean timedOut;

    public CubeException(String message, boolean timedOut) {
        super(message);
        this.timedOut = timedOut;
    }

    /** Whether the call ran out of time (connect or read timeout) rather than failing. */
    public boolean timedOut() {
        return timedOut;
    }
}
