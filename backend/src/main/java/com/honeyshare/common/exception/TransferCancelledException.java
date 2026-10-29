package com.honeyshare.common.exception;

public class TransferCancelledException extends DomainException {
    public TransferCancelledException(String transferId) {
        super("TRANSFER_CANCELLED", "Transfer has been cancelled: " + transferId);
    }
}

