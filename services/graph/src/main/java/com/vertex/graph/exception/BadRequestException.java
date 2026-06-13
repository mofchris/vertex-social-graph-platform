package com.vertex.graph.exception;

/** Invalid request, e.g. a self-edge (follow/friend/block yourself). Maps to HTTP 400. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
