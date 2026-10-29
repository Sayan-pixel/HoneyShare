package com.honeyshare.common.exception;

public class TransferExpiredException extends DomainException {
    public TransferExpiredException(String transferId) {
        super("TRANSFER_EXPIRED", "Transfer has expired: " + transferId);
    }
}

