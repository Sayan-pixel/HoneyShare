package com.honeyshare.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Utility for generating and hashing secret tokens.
 *
 * <p>The raw token is generated with a cryptographically secure random source
 * and encoded as URL-safe Base64. Only its SHA-256 hex digest is stored in the
 * database so a database compromise does not expose redeemable tokens.
 *
 * <p>IMPORTANT: Never log or persist the raw token value returned by
 * {@link #generateRawToken()}. Send it once over a secure channel (e.g., email
 * link or TOTP confirmation), then discard it.
 */
public final class SecureTokens {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32; // 256-bit entropy

    private SecureTokens() {}

    /**
     * Generate a cryptographically secure random token.
     *
     * @return URL-safe Base64-encoded token string (no padding).
     */
    public static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Hash a raw token for database storage.
     *
     * @param rawToken the token returned by {@link #generateRawToken()}.
     * @return lowercase hex SHA-256 digest.
     */
    public static String hash(String rawToken) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JVM spec
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Constant-time comparison to protect against timing attacks.
     */
    public static boolean matches(String rawToken, String storedHash) {
        return MessageDigest.isEqual(
                hash(rawToken).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}

