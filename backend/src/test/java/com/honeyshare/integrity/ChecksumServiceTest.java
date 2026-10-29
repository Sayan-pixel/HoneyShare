package com.honeyshare.integrity;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumServiceTest {

    private final ChecksumService checksumService = new ChecksumService();

    @Test
    void calculateSha256_computesKnownHash() {
        // Known SHA-256 for "hello world": b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
        String input = "hello world";
        ByteArrayInputStream bais = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));

        String hash = checksumService.calculateSha256(bais);
        assertThat(hash).isEqualTo("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9");
    }

    @Test
    void verifySha256_caseInsensitiveMatch() {
        String hash1 = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
        String hash2 = "B94D27B9934D3E08A52E52D7DA7DABFAC484EFE37A5380EE9088F7ACE2EFCDE9";

        assertThat(checksumService.verifySha256(hash1, hash2)).isTrue();
        assertThat(checksumService.verifySha256(hash1, "wronghash")).isFalse();
    }
}

