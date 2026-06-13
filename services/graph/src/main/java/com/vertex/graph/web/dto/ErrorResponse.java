package com.vertex.graph.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** Consistent error shape across the API. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String error,
        String message,
        Map<String, String> fieldErrors
) {
    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, null);
    }
}
