package com.honeyshare.common.exception;

/**
 * Thrown when an endpoint or operation requiring E2E encryption is invoked
 * while the E2E feature flag is disabled pending security review.
 */
public class E2eFeatureDisabledException extends DomainException {

    public E2eFeatureDisabledException() {
        super("E2E_FEATURE_DISABLED", "End-to-End Encryption (E2E) mode is currently disabled pending cryptographic protocol review.");
    }
}

