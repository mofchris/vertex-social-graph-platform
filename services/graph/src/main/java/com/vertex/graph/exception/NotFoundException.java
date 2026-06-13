package com.vertex.graph.exception;

/**
 * No such relationship/request — also used to fail block-protected actions *silently*
 * (don't reveal that the other user blocked you). Maps to HTTP 404.
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
