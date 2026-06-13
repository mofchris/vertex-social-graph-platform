package com.vertex.identity.exception;

/** Thrown when a refresh token is unknown, expired, or reused. Maps to HTTP 401. */
public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
