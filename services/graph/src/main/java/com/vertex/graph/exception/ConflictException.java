package com.vertex.graph.exception;

/** Invalid state transition, e.g. accepting your own friend request. Maps to HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
