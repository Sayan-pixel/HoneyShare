package com.honeyshare.encryption.adapter.web;

import com.honeyshare.encryption.service.DeviceService;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.domain.UserSession;
import com.honeyshare.security.AuthenticatedPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceController Unit Tests")
class DeviceControllerTest {

    @Mock
    private DeviceService deviceService;

    private DeviceController controller;
    private User testUser;
    private UserSession testSession;

    @BeforeEach
    void setUp() {
        controller = new DeviceController(deviceService);

        testUser = User.create("alice@example.com", "hash", UserRole.USER);
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
    @DisplayName("POST /me/devices registers device and returns 201 Created")
    void registerDevice_returnsCreated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");

        DeviceService.DeviceDto dto = new DeviceService.DeviceDto(
                "dev-1", "MacBook", "MACOS", "pubkey-b64", Instant.now(), Instant.now(), null, false);

        when(deviceService.registerDevice(eq(testUser.getId()), eq("MacBook"), eq("MACOS"), eq("pubkey-b64"), eq("192.168.1.50")))
                .thenReturn(dto);

        ResponseEntity<DeviceService.DeviceDto> response = controller.registerDevice(
                new DeviceController.RegisterDeviceRequest("MacBook", "MACOS", "pubkey-b64"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("GET /me/devices returns list of devices")
    void getMyDevices_returnsList() {
        DeviceService.DeviceDto dto = new DeviceService.DeviceDto(
                "dev-1", "MacBook", "MACOS", "pubkey-b64", Instant.now(), Instant.now(), null, false);
        when(deviceService.getMyDevices(testUser.getId())).thenReturn(List.of(dto));

        ResponseEntity<List<DeviceService.DeviceDto>> response = controller.getMyDevices();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
    }

    @Test
    @DisplayName("POST /me/devices/{id}/revoke revokes device")
    void revokeDevice_returnsOk() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.50");

        ResponseEntity<Map<String, String>> response = controller.revokeDevice("dev-1", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("status", "REVOKED");
        verify(deviceService).revokeDevice("dev-1", testUser.getId(), "192.168.1.50");
    }

    @Test
    @DisplayName("GET /{userId}/devices/active returns active device keys")
    void getActiveDeviceKeys_returnsList() {
        DeviceService.ActiveDeviceKeyDto dto = new DeviceService.ActiveDeviceKeyDto("dev-1", "MacBook", "MACOS", "pubkey-b64");
        when(deviceService.getActiveDeviceKeys("user-bob")).thenReturn(List.of(dto));

        ResponseEntity<List<DeviceService.ActiveDeviceKeyDto>> response = controller.getActiveDeviceKeys("user-bob");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(dto);
    }
}

