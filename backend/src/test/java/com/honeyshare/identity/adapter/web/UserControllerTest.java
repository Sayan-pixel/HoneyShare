package com.honeyshare.identity.adapter.web;

import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.domain.UserSession;
import com.honeyshare.identity.port.UserRepository;
import com.honeyshare.identity.service.MfaService;
import com.honeyshare.quotas.QuotaService;
import com.honeyshare.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserController Unit Tests")
class UserControllerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private MfaService mfaService;

    @Mock
    private QuotaService quotaService;

    private UserController controller;
    private User testUser;
    private UserSession testSession;

    @BeforeEach
    void setUp() {
        controller = new UserController(userRepository, mfaService, quotaService);

        testUser = User.create("test@example.com", "hash", UserRole.USER);
        testUser.verifyEmail(Instant.now());
        testSession = UserSession.create(testUser.getId(), "token-hash", Instant.now().plusSeconds(3600), "127.0.0.1", "JUnit");

        AuthenticatedPrincipal principal = new AuthenticatedPrincipal(testUser, testSession);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET /me returns user profile and MFA status")
    void getMe_returnsProfile() {
        when(userRepository.findById(testUser.getId())).thenReturn(Optional.of(testUser));
        when(mfaService.hasMfaEnabled(testUser.getId())).thenReturn(true);

        ResponseEntity<UserController.UserProfileDto> response = controller.getMe();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        UserController.UserProfileDto dto = response.getBody();
        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(testUser.getId());
        assertThat(dto.email()).isEqualTo("test@example.com");
        assertThat(dto.role()).isEqualTo("USER");
        assertThat(dto.status()).isEqualTo("ACTIVE");
        assertThat(dto.emailVerified()).isTrue();
        assertThat(dto.mfaEnabled()).isTrue();
    }

    @Test
    @DisplayName("GET /me/quota returns quota status")
    void getMyQuota_returnsQuotaStatus() {
        QuotaService.QuotaStatus status = new QuotaService.QuotaStatus(
                200L * 1024 * 1024 * 1024,
                10L * 1024 * 1024 * 1024,
                5L * 1024 * 1024 * 1024,
                185L * 1024 * 1024 * 1024
        );
        when(quotaService.getQuotaStatus(testUser.getId())).thenReturn(status);

        ResponseEntity<QuotaService.QuotaStatus> response = controller.getMyQuota();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isEqualTo(status);
    }
}

