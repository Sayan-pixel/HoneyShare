package com.honeyshare.identity.adapter.web;

import com.honeyshare.common.exception.DomainException;
import com.honeyshare.common.exception.RateLimitExceededException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler that maps domain exceptions and validation errors
 * to structured JSON error responses.
 *
 * <p>IMPORTANT: Error responses must never include stack traces, internal
 * exception messages that reference storage or infrastructure, passwords,
 * tokens, or presigned URLs.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorResponse> handleDomainException(DomainException ex) {
        HttpStatus status = mapStatus(ex);
        log.info("Domain error [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity.status(status)
                .body(new ErrorResponse(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ErrorResponse(ex.getErrorCode(), "Too many requests. Please try again later."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_ERROR", details));
    }

    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ErrorResponse> handleSecurityException(SecurityException ex) {
        log.warn("Security violation detected: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("SECURITY_VIOLATION", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("BAD_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        // Log full exception for internal diagnosis, but return generic message
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("INTERNAL_ERROR", "An internal error occurred."));
    }

    private HttpStatus mapStatus(DomainException ex) {
        return switch (ex.getErrorCode()) {
            case "AUTH_REQUIRED" -> HttpStatus.UNAUTHORIZED;
            case "MFA_REQUIRED", "EMAIL_NOT_VERIFIED", "RECIPIENT_NOT_AUTHORIZED", "E2E_FEATURE_DISABLED" -> HttpStatus.FORBIDDEN;
            case "TRANSFER_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TRANSFER_EXPIRED" -> HttpStatus.GONE;
            case "TRANSFER_CANCELLED" -> HttpStatus.CONFLICT;
            case "QUOTA_EXCEEDED" -> HttpStatus.PAYLOAD_TOO_LARGE;
            case "CHECKSUM_MISMATCH" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "INVITATION_INVALID" -> HttpStatus.BAD_REQUEST;
            case "RATE_LIMIT_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    public record ErrorResponse(String code, String message) {}
}

