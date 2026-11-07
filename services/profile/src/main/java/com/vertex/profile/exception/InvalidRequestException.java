package com.vertex.profile.exception;

/**
 * Thrown for a malformed request parameter that bean validation can't express (e.g. a
 * comma-separated id list). Maps to HTTP 400 in the same shape as body validation errors.
 */
public class InvalidRequestException extends RuntimeException {

    private final String field;

    public InvalidRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
