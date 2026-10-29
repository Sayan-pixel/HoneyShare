package com.honeyshare.common.exception;

public class TransferNotFoundException extends DomainException {
    public TransferNotFoundException(String transferId) {
        super("TRANSFER_NOT_FOUND", "Transfer not found: " + transferId);
    }
}

