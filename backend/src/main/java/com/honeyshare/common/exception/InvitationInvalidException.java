package com.honeyshare.common.exception;

public class InvitationInvalidException extends DomainException {
    public InvitationInvalidException(String reason) {
        super("INVITATION_INVALID", "Invitation is not valid: " + reason);
    }
}

