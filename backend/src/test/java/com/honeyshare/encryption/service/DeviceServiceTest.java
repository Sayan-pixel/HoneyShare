package com.honeyshare.encryption.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.EmailNotVerifiedException;
import com.honeyshare.encryption.domain.Device;
import com.honeyshare.encryption.port.DeviceRepository;
import com.honeyshare.encryption.service.DeviceService.ActiveDeviceKeyDto;
import com.honeyshare.encryption.service.DeviceService.DeviceDto;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.port.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceService Unit Tests")
class DeviceServiceTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    private DeviceService deviceService;

    private static final String PUBKEY = "MCowBQYDK2VuAyEA/r8e1f57mK8c5r8Fq7Z3E4N7uG6d1x9L+2o=";

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService(deviceRepository, userRepository, auditService);
    }

    @Test
    @DisplayName("Registers device for verified user")
    void registersDeviceForVerifiedUser() {
        User user = User.create("alice@example.com", "hash", UserRole.USER);
        user.verifyEmail(Instant.now());

        when(userRepository.findById("user-alice")).thenReturn(Optional.of(user));

        DeviceDto dto = deviceService.registerDevice("user-alice", "My Laptop", "LINUX", PUBKEY, "127.0.0.1");

        assertThat(dto).isNotNull();
        assertThat(dto.deviceName()).isEqualTo("My Laptop");
        assertThat(dto.platform()).isEqualTo("LINUX");
        assertThat(dto.publicKeyB64()).isEqualTo(PUBKEY);
        assertThat(dto.revoked()).isFalse();

        verify(deviceRepository).save(any(Device.class));
        verify(auditService).record(eq("user-alice"), eq("DEVICE_REGISTERED"), eq("device"), any(), eq("127.0.0.1"));
    }

    @Test
    @DisplayName("Rejects device registration if user email is not verified")
    void rejectsDeviceRegistrationIfEmailNotVerified() {
        User user = User.create("bob@example.com", "hash", UserRole.USER); // Not verified

        when(userRepository.findById("user-bob")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> deviceService.registerDevice("user-bob", "Phone", "IOS", PUBKEY, "127.0.0.1"))
                .isInstanceOf(EmailNotVerifiedException.class);

        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("Revokes device owned by caller")
    void revokesDeviceOwnedByCaller() {
        Device device = Device.register("user-alice", "Work PC", "WINDOWS", PUBKEY);
        when(deviceRepository.findByIdAndUserId(device.getId(), "user-alice")).thenReturn(Optional.of(device));

        deviceService.revokeDevice(device.getId(), "user-alice", "127.0.0.1");

        assertThat(device.isRevoked()).isTrue();
        verify(deviceRepository).save(device);
        verify(auditService).record("user-alice", "DEVICE_REVOKED", "device", device.getId(), "127.0.0.1");
    }

    @Test
    @DisplayName("Revoke is idempotent if device is already revoked")
    void revokeIsIdempotent() {
        Device device = Device.register("user-alice", "Work PC", "WINDOWS", PUBKEY);
        device.revoke(Instant.now());
        when(deviceRepository.findByIdAndUserId(device.getId(), "user-alice")).thenReturn(Optional.of(device));

        deviceService.revokeDevice(device.getId(), "user-alice", "127.0.0.1");

        verify(deviceRepository, never()).save(device);
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Rejects revoke if device is not owned by user")
    void rejectsRevokeIfNotOwner() {
        when(deviceRepository.findByIdAndUserId("device-99", "user-intruder")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceService.revokeDevice("device-99", "user-intruder", "127.0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found or not owned by user");
    }

    @Test
    @DisplayName("Returns list of my devices")
    void getsMyDevices() {
        Device d1 = Device.register("user-alice", "Dev 1", "LINUX", PUBKEY);
        Device d2 = Device.register("user-alice", "Dev 2", "ANDROID", PUBKEY);
        when(deviceRepository.findByUserIdOrderByRegisteredAtDesc("user-alice")).thenReturn(List.of(d1, d2));

        List<DeviceDto> list = deviceService.getMyDevices("user-alice");

        assertThat(list).hasSize(2);
        assertThat(list.get(0).deviceName()).isEqualTo("Dev 1");
        assertThat(list.get(1).deviceName()).isEqualTo("Dev 2");
    }

    @Test
    @DisplayName("Returns active device keys of target user")
    void getsActiveDeviceKeys() {
        Device active = Device.register("user-bob", "Bob Phone", "IOS", PUBKEY);
        when(deviceRepository.findActiveDevicesByUserId("user-bob")).thenReturn(List.of(active));

        List<ActiveDeviceKeyDto> keys = deviceService.getActiveDeviceKeys("user-bob");

        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).deviceId()).isEqualTo(active.getId());
        assertThat(keys.get(0).publicKeyB64()).isEqualTo(PUBKEY);
    }
}

