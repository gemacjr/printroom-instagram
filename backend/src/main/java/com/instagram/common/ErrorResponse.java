package com.instagram.common;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, Instant timestamp) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, null, Instant.now());
    }
}
