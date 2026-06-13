package com.vertex.profile.exception;

/** Thrown when a profile doesn't exist, or is hidden from the viewer. Maps to HTTP 404. */
public class ProfileNotFoundException extends RuntimeException {
    public ProfileNotFoundException(String message) {
        super(message);
    }
}
