package com.honeyshare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration bound from {@code honeyshare.auth.*} in application.yml.
 */
@ConfigurationProperties(prefix = "honeyshare.auth")
public class AuthProperties {

    private int sessionDurationMinutes = 1440;
    private int invitationExpiryHours = 72;
    private int emailVerificationExpiryHours = 24;
    private int passwordResetExpiryMinutes = 60;
    private int maxLoginAttempts = 5;
    private int maxMfaAttempts = 5;
    private Argon2 argon2 = new Argon2();

    public static class Argon2 {
        private int memoryKibibytes = 65536;
        private int iterations = 3;
        private int parallelism = 1;
        private int hashLength = 32;
        private int saltLength = 16;

        public int getMemoryKibibytes() { return memoryKibibytes; }
        public void setMemoryKibibytes(int v) { this.memoryKibibytes = v; }
        public int getIterations() { return iterations; }
        public void setIterations(int v) { this.iterations = v; }
        public int getParallelism() { return parallelism; }
        public void setParallelism(int v) { this.parallelism = v; }
        public int getHashLength() { return hashLength; }
        public void setHashLength(int v) { this.hashLength = v; }
        public int getSaltLength() { return saltLength; }
        public void setSaltLength(int v) { this.saltLength = v; }
    }

    public int getSessionDurationMinutes() { return sessionDurationMinutes; }
    public void setSessionDurationMinutes(int v) { this.sessionDurationMinutes = v; }
    public int getInvitationExpiryHours() { return invitationExpiryHours; }
    public void setInvitationExpiryHours(int v) { this.invitationExpiryHours = v; }
    public int getEmailVerificationExpiryHours() { return emailVerificationExpiryHours; }
    public void setEmailVerificationExpiryHours(int v) { this.emailVerificationExpiryHours = v; }
    public int getPasswordResetExpiryMinutes() { return passwordResetExpiryMinutes; }
    public void setPasswordResetExpiryMinutes(int v) { this.passwordResetExpiryMinutes = v; }
    public int getMaxLoginAttempts() { return maxLoginAttempts; }
    public void setMaxLoginAttempts(int v) { this.maxLoginAttempts = v; }
    public int getMaxMfaAttempts() { return maxMfaAttempts; }
    public void setMaxMfaAttempts(int v) { this.maxMfaAttempts = v; }
    public Argon2 getArgon2() { return argon2; }
    public void setArgon2(Argon2 v) { this.argon2 = v; }
}

