package com.vertex.notify.exception;

/**
 * Nothing to show — or nothing the caller may see. Used for "not processed yet" and "not yours"
 * alike, so a lookup never confirms that someone else's event id exists. Maps to HTTP 404.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
