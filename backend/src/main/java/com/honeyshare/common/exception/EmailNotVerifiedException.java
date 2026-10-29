package com.honeyshare.common.exception;

public class EmailNotVerifiedException extends DomainException {
    public EmailNotVerifiedException() {
        super("EMAIL_NOT_VERIFIED", "Email address must be verified before accessing files.");
    }
}

