package com.github.hgdcoder.flowershow.recommendation;

import java.util.Map;
import org.springframework.http.HttpStatus;

public final class RecommendationException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public RecommendationException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public RecommendationException(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details
    ) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
