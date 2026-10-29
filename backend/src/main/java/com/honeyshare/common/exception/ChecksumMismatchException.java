package com.honeyshare.common.exception;

public class ChecksumMismatchException extends DomainException {
    public ChecksumMismatchException(String expected, String actual) {
        super("CHECKSUM_MISMATCH", "Cryptographic checksum mismatch: expected=" + expected + ", actual=" + actual);
    }
}

