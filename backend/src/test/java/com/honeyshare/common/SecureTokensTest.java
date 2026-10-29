package com.honeyshare.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecureTokensTest {

    @Test
    void generateRawToken_producesUniqueHighEntropyStrings() {
        String t1 = SecureTokens.generateRawToken();
        String t2 = SecureTokens.generateRawToken();
        assertThat(t1).isNotEqualTo(t2);
        // URL-safe Base64, ~43 chars for 32 bytes without padding
        assertThat(t1).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    void hash_isDeterministicAndHex() {
        String token = SecureTokens.generateRawToken();
        String hash1 = SecureTokens.hash(token);
        String hash2 = SecureTokens.hash(token);
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).matches("[0-9a-f]{64}"); // SHA-256 = 32 bytes = 64 hex chars
    }

    @Test
    void hash_differentTokensProduceDifferentHashes() {
        String h1 = SecureTokens.hash("token-a");
        String h2 = SecureTokens.hash("token-b");
        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void matches_returnsTrueForCorrectToken() {
        String raw = SecureTokens.generateRawToken();
        String hash = SecureTokens.hash(raw);
        assertThat(SecureTokens.matches(raw, hash)).isTrue();
    }

    @Test
    void matches_returnsFalseForWrongToken() {
        String hash = SecureTokens.hash("correct-token");
        assertThat(SecureTokens.matches("wrong-token", hash)).isFalse();
    }
}

