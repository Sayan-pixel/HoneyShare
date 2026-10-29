package com.honeyshare.common.exception;

public class QuotaExceededException extends DomainException {
    public QuotaExceededException(String message) {
        super("QUOTA_EXCEEDED", message);
    }
}

