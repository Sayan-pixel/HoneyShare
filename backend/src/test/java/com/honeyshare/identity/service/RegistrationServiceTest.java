package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.SecureTokens;
import com.honeyshare.common.exception.InvitationInvalidException;
import com.honeyshare.config.AuthProperties;
import com.honeyshare.identity.domain.*;
import com.honeyshare.identity.port.EmailVerificationTokenRepository;
import com.honeyshare.identity.port.InvitationRepository;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.security.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    @Mock UserRepository userRepository;
    @Mock InvitationRepository invitationRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock EmailVerificationService emailVerificationService;
    @Mock AuditService auditService;
    @Mock RateLimiter rateLimiter;

    RegistrationService service;

    @BeforeEach
    void setUp() {
        service = new RegistrationService(
                userRepository, invitationRepository, passwordEncoder,
                emailVerificationService, auditService, rateLimiter);
    }

    @Test
    void register_withValidInvitation_createsUser() {
        String rawToken = SecureTokens.generateRawToken();
        Invitation inv = Invitation.create(
                "alice@example.com",
                SecureTokens.hash(rawToken),
                "admin-01",
                Instant.now().plus(72, ChronoUnit.HOURS)
        );

        when(invitationRepository.findByTokenHash(SecureTokens.hash(rawToken)))
                .thenReturn(Optional.of(inv));
        when(userRepository.existsByEmailNormalized("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("$argon2id$...");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User user = service.register(rawToken, "alice@example.com", "StrongPass123!", "127.0.0.1");

        assertThat(user).isNotNull();
        assertThat(user.getEmailNormalized()).isEqualTo("alice@example.com");
        assertThat(user.getStatus()).isEqualTo(UserStatus.INVITED);

        verify(emailVerificationService).sendVerificationEmail(any(User.class), eq("127.0.0.1"));
        verify(invitationRepository).save(argThat(i -> i.getRedeemedAt() != null));
    }

    @Test
    void register_withExpiredInvitation_throws() {
        String rawToken = SecureTokens.generateRawToken();
        Invitation inv = Invitation.create(
                "alice@example.com",
                SecureTokens.hash(rawToken),
                "admin-01",
                Instant.now().minus(1, ChronoUnit.SECONDS) // already expired
        );

        when(invitationRepository.findByTokenHash(SecureTokens.hash(rawToken)))
                .thenReturn(Optional.of(inv));

        assertThatThrownBy(() ->
                service.register(rawToken, "alice@example.com", "StrongPass123!", "127.0.0.1"))
                .isInstanceOf(InvitationInvalidException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_withWrongEmail_throws() {
        String rawToken = SecureTokens.generateRawToken();
        Invitation inv = Invitation.create(
                "bob@example.com",  // invitation bound to bob
                SecureTokens.hash(rawToken),
                "admin-01",
                Instant.now().plus(72, ChronoUnit.HOURS)
        );

        when(invitationRepository.findByTokenHash(SecureTokens.hash(rawToken)))
                .thenReturn(Optional.of(inv));

        assertThatThrownBy(() ->
                service.register(rawToken, "alice@example.com", "StrongPass123!", "127.0.0.1"))
                .isInstanceOf(InvitationInvalidException.class);
    }

    @Test
    void register_withDuplicateEmail_doesNotRevealAccountExistence() {
        String rawToken = SecureTokens.generateRawToken();
        Invitation inv = Invitation.create(
                "alice@example.com",
                SecureTokens.hash(rawToken),
                "admin-01",
                Instant.now().plus(72, ChronoUnit.HOURS)
        );

        when(invitationRepository.findByTokenHash(SecureTokens.hash(rawToken)))
                .thenReturn(Optional.of(inv));
        when(userRepository.existsByEmailNormalized("alice@example.com")).thenReturn(true);

        // Should throw InvitationInvalidException (not "email already exists") to avoid enumeration
        assertThatThrownBy(() ->
                service.register(rawToken, "alice@example.com", "StrongPass123!", "127.0.0.1"))
                .isInstanceOf(InvitationInvalidException.class);
    }

    @Test
    void register_withUnknownToken_throws() {
        String rawToken = SecureTokens.generateRawToken();
        when(invitationRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.register(rawToken, "alice@example.com", "StrongPass123!", "127.0.0.1"))
                .isInstanceOf(InvitationInvalidException.class);
    }

    @Test
    void register_normalizesEmailToLowercase() {
        String rawToken = SecureTokens.generateRawToken();
        Invitation inv = Invitation.create(
                "alice@example.com",
                SecureTokens.hash(rawToken),
                "admin-01",
                Instant.now().plus(72, ChronoUnit.HOURS)
        );

        when(invitationRepository.findByTokenHash(SecureTokens.hash(rawToken)))
                .thenReturn(Optional.of(inv));
        when(userRepository.existsByEmailNormalized("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("$argon2id$...");
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(invitationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        User user = service.register(rawToken, "ALICE@EXAMPLE.COM", "StrongPass123!", "127.0.0.1");

        assertThat(user.getEmailNormalized()).isEqualTo("alice@example.com");
    }
}

