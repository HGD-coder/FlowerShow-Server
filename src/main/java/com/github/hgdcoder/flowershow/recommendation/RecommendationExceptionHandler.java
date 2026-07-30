package com.github.hgdcoder.flowershow.recommendation;

import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.ApiEnvelope;
import com.github.hgdcoder.flowershow.recommendation.RecommendationModels.ApiError;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RecommendationController.class)
public final class RecommendationExceptionHandler {

    @ExceptionHandler(RecommendationException.class)
    public ResponseEntity<ApiEnvelope<Object>> recommendationError(RecommendationException exception) {
        return ResponseEntity.status(exception.status()).body(error(
                exception.code(),
                exception.getMessage(),
                exception.details()
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiEnvelope<Object>> invalidBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(error(
                "INVALID_REQUEST_BODY",
                "Request body is invalid.",
                null
        ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiEnvelope<Object>> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(error(
                "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json.",
                null
        ));
    }

    private static ApiEnvelope<Object> error(
            String code,
            String message,
            java.util.Map<String, Object> details
    ) {
        return new ApiEnvelope<>(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", ""),
                System.currentTimeMillis(),
                null,
                new ApiError(code, message, details)
        );
    }
}
