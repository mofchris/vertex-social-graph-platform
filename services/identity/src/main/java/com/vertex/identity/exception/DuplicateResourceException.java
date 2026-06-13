package com.vertex.identity.exception;

/** Thrown when an email or username is already taken. Maps to HTTP 409. */
public class DuplicateResourceException extends RuntimeException {
    public DuplicateResourceException(String message) {
        super(message);
    }
}
