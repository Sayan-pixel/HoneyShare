package com.honeyshare.identity.adapter.web;

import com.honeyshare.common.exception.AuthenticationException;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.identity.service.MfaService;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.security.SecurityContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository userRepository;
    private final MfaService mfaService;
    private final QuotaService quotaService;

    public UserController(
            UserRepository userRepository,
            MfaService mfaService,
            QuotaService quotaService
    ) {
        this.userRepository = userRepository;
        this.mfaService = mfaService;
        this.quotaService = quotaService;
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileDto> getMe() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        User user = userRepository.findById(principal.userId())
                .orElseThrow(() -> new AuthenticationException("User not found"));

        boolean mfaEnabled = mfaService.hasMfaEnabled(user.getId());

        return ResponseEntity.ok(new UserProfileDto(
                user.getId(),
                user.getEmailNormalized(),
                user.getRole().name(),
                user.getStatus().name(),
                user.isEmailVerified(),
                user.getEmailVerifiedAt(),
                mfaEnabled,
                user.getCreatedAt(),
                user.getLastLoginAt()
        ));
    }

    @GetMapping("/me/quota")
    public ResponseEntity<QuotaService.QuotaStatus> getMyQuota() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        QuotaService.QuotaStatus status = quotaService.getQuotaStatus(principal.userId());
        return ResponseEntity.ok(status);
    }

    public record UserProfileDto(
            String id,
            String email,
            String role,
            String status,
            boolean emailVerified,
            Instant emailVerifiedAt,
            boolean mfaEnabled,
            Instant createdAt,
            Instant lastLoginAt
    ) {}
}
