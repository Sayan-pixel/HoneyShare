package com.honeyshare.common.exception;

public class MfaRequiredException extends DomainException {
    public MfaRequiredException() {
        super("MFA_REQUIRED", "Multi-factor authentication is required.");
    }
}

