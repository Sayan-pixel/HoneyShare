package com.honeyshare.common.exception;

public class AuthenticationException extends DomainException {
    public AuthenticationException(String message) {
        super("AUTH_REQUIRED", message);
    }
}

