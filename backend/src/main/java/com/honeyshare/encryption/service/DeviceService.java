package com.honeyshare.encryption.service;

import com.honeyshare.audit.AuditService;
import com.honeyshare.common.exception.EmailNotVerifiedException;
import com.honeyshare.encryption.domain.Device;
import com.honeyshare.encryption.port.DeviceRepository;
import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;

    public DeviceService(
            DeviceRepository deviceRepository,
            UserRepository userRepository,
            AuditService auditService
    ) {
        this.deviceRepository = deviceRepository;
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public DeviceDto registerDevice(String userId, String deviceName, String platform, String publicKeyB64, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        if (!user.canAccessFiles()) {
            throw new EmailNotVerifiedException();
        }

        Device device = Device.register(userId, deviceName, platform, publicKeyB64);
        deviceRepository.save(device);

        log.info("Registered device: id={} userId={} platform={}", device.getId(), userId, platform);
        auditService.record(userId, "DEVICE_REGISTERED", "device", device.getId(), ipAddress);

        return toDto(device);
    }

    @Transactional
    public void revokeDevice(String deviceId, String userId, String ipAddress) {
        Device device = deviceRepository.findByIdAndUserId(deviceId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Device not found or not owned by user: " + deviceId));

        if (device.isRevoked()) {
            return; // Idempotent
        }

        device.revoke(Instant.now());
        deviceRepository.save(device);

        log.info("Revoked device: id={} userId={}", deviceId, userId);
        auditService.record(userId, "DEVICE_REVOKED", "device", deviceId, ipAddress);
    }

    @Transactional(readOnly = true)
    public List<DeviceDto> getMyDevices(String userId) {
        return deviceRepository.findByUserIdOrderByRegisteredAtDesc(userId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ActiveDeviceKeyDto> getActiveDeviceKeys(String targetUserId) {
        return deviceRepository.findActiveDevicesByUserId(targetUserId).stream()
                .map(d -> new ActiveDeviceKeyDto(d.getId(), d.getDeviceName(), d.getPlatform(), d.getPublicKeyB64()))
                .toList();
    }

    private DeviceDto toDto(Device d) {
        return new DeviceDto(
                d.getId(), d.getDeviceName(), d.getPlatform(), d.getPublicKeyB64(),
                d.getRegisteredAt(), d.getLastSeenAt(), d.getRevokedAt(), d.isRevoked());
    }

    public record DeviceDto(
            String id, String deviceName, String platform, String publicKeyB64,
            Instant registeredAt, Instant lastSeenAt, Instant revokedAt, boolean revoked
    ) {}

    public record ActiveDeviceKeyDto(
            String deviceId, String deviceName, String platform, String publicKeyB64
    ) {}
}

