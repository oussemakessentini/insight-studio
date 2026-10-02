package com.oussamaksantini.insightstudio.common.web;

import org.springframework.http.HttpStatus;

/**
 * A value that breaks a field rule. A 400 when it reaches the API; the CSV import catches it and
 * reports the same message as a row error instead.
 */
public class InvalidFieldException extends ApiException {

    public InvalidFieldException(String detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }
}
