package com.honeyshare.integrity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Streaming cryptographic checksum calculation and verification.
 * Streams data through a fixed-size buffer to enforce zero whole-file memory buffering.
 */
@Service
public class ChecksumService {

    private static final Logger log = LoggerFactory.getLogger(ChecksumService.class);
    private static final int BUFFER_SIZE = 64 * 1024; // 64 KB buffer

    /**
     * Compute SHA-256 hex hash of an InputStream in a streaming manner.
     *
     * @param inputStream the stream to hash (will be consumed to EOF)
     * @return lowercase hex string of the SHA-256 digest
     */
    public String calculateSha256(InputStream inputStream) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate SHA-256 checksum", e);
        }
    }

    /**
     * Verify whether calculated hash matches expected hash (case-insensitive).
     */
    public boolean verifySha256(String calculatedHash, String expectedHash) {
        if (calculatedHash == null || expectedHash == null) return false;
        return calculatedHash.equalsIgnoreCase(expectedHash.strip());
    }
}

