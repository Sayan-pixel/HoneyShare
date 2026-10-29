package com.honeyshare.identity.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.SecureTokens;
import com.honeyshare.common.exception.AuthenticationException;
import com.honeyshare.config.AuthProperties;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.domain.UserSession;
import com.honeyshare.identity.domain.UserStatus;
import com.honeyshare.identity.port.SessionRepository;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.security.RateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationServiceTest {

    @Mock UserRepository userRepository;
    @Mock SessionRepository sessionRepository;
    @Mock MfaService mfaService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock AuditService auditService;
    @Mock RateLimiter rateLimiter;

    AuthProperties authProperties;
    AuthenticationService service;

    @BeforeEach
    void setUp() {
        authProperties = new AuthProperties();
        service = new AuthenticationService(
                userRepository, sessionRepository, mfaService,
                passwordEncoder, authProperties, auditService, rateLimiter);
    }

    @Test
    void login_withValidCredentials_returnsSessionToken() {
        User user = User.create("alice@example.com", "$argon2id$hashed", UserRole.USER);
        user.verifyEmail(java.time.Instant.now());

        when(userRepository.findByEmailNormalized("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "$argon2id$hashed")).thenReturn(true);
        when(mfaService.hasMfaEnabled(user.getId())).thenReturn(false);
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.login("alice@example.com", "password123", "127.0.0.1", "TestAgent");

        assertThat(result.rawSessionToken()).isNotBlank();
        assertThat(result.sessionId()).isNotBlank();
        assertThat(result.mfaRequired()).isFalse();

        verify(sessionRepository).save(argThat(UserSession::isMfaVerified));
    }

    @Test
    void login_withMfaEnabled_flagsMfaRequired() {
        User user = User.create("admin@example.com", "$argon2id$hashed", UserRole.ADMIN);
        user.verifyEmail(java.time.Instant.now());

        when(userRepository.findByEmailNormalized("admin@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "$argon2id$hashed")).thenReturn(true);
        when(mfaService.hasMfaEnabled(user.getId())).thenReturn(true);
        when(sessionRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        var result = service.login("admin@example.com", "password123", "127.0.0.1", "TestAgent");

        assertThat(result.mfaRequired()).isTrue();
        verify(sessionRepository).save(argThat(s -> !s.isMfaVerified()));
    }

    @Test
    void login_withWrongPassword_throwsAuthenticationException() {
        User user = User.create("alice@example.com", "$argon2id$hashed", UserRole.USER);

        when(userRepository.findByEmailNormalized("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrongpassword", "$argon2id$hashed")).thenReturn(false);

        assertThatThrownBy(() ->
                service.login("alice@example.com", "wrongpassword", "127.0.0.1", "TestAgent"))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void login_withNonexistentUser_throwsAuthenticationException() {
        when(userRepository.findByEmailNormalized("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.login("nobody@example.com", "password123", "127.0.0.1", "TestAgent"))
                .isInstanceOf(AuthenticationException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    void logout_revokesSession() {
        String rawToken = SecureTokens.generateRawToken();
        String hash = SecureTokens.hash(rawToken);
        UserSession session = UserSession.create(
                "user-1", hash, java.time.Instant.now().plusSeconds(3600), "127.0.0.1", "Agent");

        when(sessionRepository.findBySessionTokenHash(hash)).thenReturn(Optional.of(session));

        service.logout(rawToken, "127.0.0.1");

        assertThat(session.isValid()).isFalse();
        verify(sessionRepository).save(session);
    }
}

