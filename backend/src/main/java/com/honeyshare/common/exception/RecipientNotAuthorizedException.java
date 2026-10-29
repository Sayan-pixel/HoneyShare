package com.honeyshare.common.exception;

public class RecipientNotAuthorizedException extends DomainException {
    public RecipientNotAuthorizedException(String message) {
        super("RECIPIENT_NOT_AUTHORIZED", message);
    }
}

