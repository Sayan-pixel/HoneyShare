package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.AuthenticationException;
import com.honeyshare.identity.domain.MfaMethod;
import com.honeyshare.identity.domain.MfaMethodType;
import com.honeyshare.identity.port.MfaMethodRepository;
import com.honeyshare.security.RateLimiter;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.QrGenerator;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * TOTP-based MFA enrollment and verification.
 *
 * <p>TOTP secrets are stored encrypted. For MVP, we use a simple XOR-with-env-key
 * approach; this must be replaced with a proper AES-256-GCM envelope before
 * the service handles real users. The encryption key is read from the environment
 * variable {@code MFA_ENCRYPTION_KEY}.
 *
 * <p><strong>MVP limitation:</strong> MFA secret encryption uses a placeholder
 * implementation. Do not use in production without replacing
 * {@link #encryptSecret} / {@link #decryptSecret} with a reviewed AES-GCM implementation.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);
    private static final String ISSUER = "HoneyShare";
    private static final int RECOVERY_CODE_COUNT = 8;

    private final MfaMethodRepository mfaMethodRepository;
    private final AuditService auditService;
    private final RateLimiter rateLimiter;
    private final PasswordEncoder recoveryCodeEncoder;

    // TOTP tooling
    private final SecretGenerator secretGenerator = new DefaultSecretGenerator(32);
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator();
    private final TimeProvider timeProvider = new SystemTimeProvider();
    private final CodeVerifier codeVerifier = new DefaultCodeVerifier(codeGenerator, timeProvider);

    public MfaService(
            MfaMethodRepository mfaMethodRepository,
            AuditService auditService,
            RateLimiter rateLimiter
    ) {
        this.mfaMethodRepository = mfaMethodRepository;
        this.auditService = auditService;
        this.rateLimiter = rateLimiter;
        // Recovery codes use bcrypt — they are one-time tokens, not passwords
        this.recoveryCodeEncoder = new BCryptPasswordEncoder(12);
    }

    /**
     * Begin TOTP enrollment. Generates a secret and returns the provisioning URI
     * and QR code data URL for the client to display.
     *
     * <p>The secret is NOT saved until the user confirms with a valid code.
     */
    public EnrollmentData beginTotpEnrollment(String userId, String email) {
        String secret = secretGenerator.generate();

        QrData qrData = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer(ISSUER)
                .build();

        String qrCodeDataUrl;
        try {
            QrGenerator qrGenerator = new ZxingPngQrGenerator();
            byte[] imageData = qrGenerator.generate(qrData);
            qrCodeDataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(imageData);
        } catch (QrGenerationException e) {
            log.warn("QR code generation failed for userId={}: {}", userId, e.getMessage());
            qrCodeDataUrl = null;
        }

        return new EnrollmentData(secret, qrData.getUri(), qrCodeDataUrl);
    }

    /**
     * Confirm TOTP enrollment by verifying the user's first code.
     * Saves the encrypted secret and generates recovery codes.
     *
     * @param userId     the user confirming enrollment
     * @param secret     the secret from {@link #beginTotpEnrollment}
     * @param code       TOTP code entered by the user
     * @param ipAddress  for audit
     * @return plaintext recovery codes (shown once; never re-returned)
     */
    @Transactional
    public List<String> confirmTotpEnrollment(String userId, String secret, String code, String ipAddress) {
        if (!codeVerifier.isValidCode(secret, code)) {
            auditService.record(userId, "MFA_ENROLL_FAILED", "user", userId, ipAddress);
            throw new AuthenticationException("Invalid TOTP code.");
        }

        // Delete any previous pending TOTP for this user
        mfaMethodRepository.findByUserId(userId).stream()
                .filter(m -> m.getMethodType() == MfaMethodType.TOTP)
                .forEach(mfaMethodRepository::delete);

        // Save enabled TOTP method
        MfaMethod totp = MfaMethod.createTotp(userId, encryptSecret(secret));
        totp.enable(Instant.now());
        mfaMethodRepository.save(totp);

        // Generate and save recovery codes
        List<String> recoveryCodes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            String code2 = generateRecoveryCode();
            recoveryCodes.add(code2);
            MfaMethod rcMethod = MfaMethod.createRecoveryCode(userId, recoveryCodeEncoder.encode(code2));
            mfaMethodRepository.save(rcMethod);
        }

        log.info("TOTP enrolled for userId={}", userId);
        auditService.record(userId, "MFA_ENROLLED", "user", userId, ipAddress);

        return recoveryCodes;
    }

    /**
     * Verify a TOTP code for the given user.
     * Throws {@link AuthenticationException} if invalid.
     */
    @Transactional
    public void verifyTotp(String userId, String code, String ipAddress) {
        rateLimiter.check("mfa", userId);

        MfaMethod totp = mfaMethodRepository.findFirstByUserIdAndMethodTypeAndEnabledAtIsNotNull(
                        userId, MfaMethodType.TOTP)
                .orElseThrow(() -> new AuthenticationException("No TOTP method enrolled."));

        String secret = decryptSecret(totp.getSecretCiphertext());
        if (!codeVerifier.isValidCode(secret, code)) {
            auditService.record(userId, "MFA_VERIFY_FAILED", "user", userId, ipAddress);
            throw new AuthenticationException("Invalid or expired TOTP code.");
        }

        totp.recordUse(Instant.now());
        mfaMethodRepository.save(totp);
    }

    /**
     * Use a recovery code. The code is marked consumed and cannot be reused.
     */
    @Transactional
    public void useRecoveryCode(String userId, String rawCode, String ipAddress) {
        rateLimiter.check("mfa-recovery", userId);

        List<MfaMethod> available = mfaMethodRepository
                .findByUserIdAndMethodTypeAndUsedAtIsNull(userId, MfaMethodType.RECOVERY_CODE);

        MfaMethod matched = available.stream()
                .filter(m -> recoveryCodeEncoder.matches(rawCode, m.getCodeHash()))
                .findFirst()
                .orElseThrow(() -> {
                    auditService.record(userId, "MFA_RECOVERY_FAILED", "user", userId, ipAddress);
                    return new AuthenticationException("Recovery code is invalid or has been used.");
                });

        matched.consumeRecoveryCode(Instant.now());
        mfaMethodRepository.save(matched);

        log.warn("Recovery code used: userId={}", userId);
        auditService.record(userId, "MFA_RECOVERY_USED", "user", userId, ipAddress);
    }

    public boolean hasMfaEnabled(String userId) {
        return mfaMethodRepository.existsByUserIdAndMethodTypeAndEnabledAtIsNotNull(userId, MfaMethodType.TOTP);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * MVP placeholder — Base64 encode only. Replace with AES-256-GCM before production.
     * TODO: Implement proper server-side secret encryption (Phase 1 hardening).
     */
    private String encryptSecret(String secret) {
        log.warn("[MVP_LIMITATION] MFA secret stored with placeholder encoding — not production-safe.");
        return "b64:" + Base64.getEncoder().encodeToString(secret.getBytes());
    }

    private String decryptSecret(String stored) {
        if (stored.startsWith("b64:")) {
            return new String(Base64.getDecoder().decode(stored.substring(4)));
        }
        throw new IllegalStateException("Unknown MFA secret encoding format.");
    }

    private String generateRecoveryCode() {
        // Format: XXXX-XXXX-XXXX (12 alphanumeric chars)
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder();
        java.util.Random rng = new java.security.SecureRandom();
        for (int i = 0; i < 12; i++) {
            if (i == 4 || i == 8) sb.append('-');
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }

    public record EnrollmentData(String secret, String provisioningUri, String qrCodeDataUrl) {}
}

