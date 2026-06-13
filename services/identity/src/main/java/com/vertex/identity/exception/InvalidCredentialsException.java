package com.vertex.identity.exception;

/** Thrown on a failed login. Deliberately vague (don't reveal which field was wrong). Maps to HTTP 401. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
