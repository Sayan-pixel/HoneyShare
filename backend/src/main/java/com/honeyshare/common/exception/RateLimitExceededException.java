package com.honeyshare.common.exception;

public class RateLimitExceededException extends DomainException {
    public RateLimitExceededException(String operation) {
        super("RATE_LIMIT_EXCEEDED", "Too many attempts for operation: " + operation);
    }
}

