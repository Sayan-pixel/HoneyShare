package com.honeyshare.common.exception;

/**
 * Base class for domain-level errors that should be surfaced to the API layer
 * as structured error responses. Subclasses carry a machine-readable error code.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected DomainException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

