package com.honeyshare.encryption.adapter.web;

import com.honeyshare.encryption.service.DeviceService;
import com.honeyshare.encryption.service.DeviceService.ActiveDeviceKeyDto;
import com.honeyshare.encryption.service.DeviceService.DeviceDto;
import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.security.SecurityContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PostMapping("/me/devices")
    public ResponseEntity<DeviceDto> registerDevice(
            @Valid @RequestBody RegisterDeviceRequest body,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        DeviceDto device = deviceService.registerDevice(
                principal.userId(),
                body.deviceName(),
                body.platform(),
                body.publicKeyB64(),
                ipOf(request)
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(device);
    }

    @GetMapping("/me/devices")
    public ResponseEntity<List<DeviceDto>> getMyDevices() {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        List<DeviceDto> devices = deviceService.getMyDevices(principal.userId());
        return ResponseEntity.ok(devices);
    }

    @PostMapping("/me/devices/{deviceId}/revoke")
    public ResponseEntity<Map<String, String>> revokeDevice(
            @PathVariable String deviceId,
            HttpServletRequest request
    ) {
        AuthenticatedPrincipal principal = SecurityContext.requirePrincipal();
        deviceService.revokeDevice(deviceId, principal.userId(), ipOf(request));
        return ResponseEntity.ok(Map.of("status", "REVOKED", "deviceId", deviceId));
    }

    @GetMapping("/{userId}/devices/active")
    public ResponseEntity<List<ActiveDeviceKeyDto>> getActiveDeviceKeys(@PathVariable String userId) {
        SecurityContext.requirePrincipal(); // Must be authenticated
        List<ActiveDeviceKeyDto> keys = deviceService.getActiveDeviceKeys(userId);
        return ResponseEntity.ok(keys);
    }

    private String ipOf(HttpServletRequest request) {
        String fwd = request.getHeader("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            return fwd.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    public record RegisterDeviceRequest(
            @NotBlank(message = "Device name is required") String deviceName,
            @NotBlank(message = "Platform is required") String platform,
            @NotBlank(message = "Public key is required") String publicKeyB64
    ) {}
}

