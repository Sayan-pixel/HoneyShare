package com.honeyshare.encryption.port;

import com.honeyshare.encryption.domain.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, String> {

    List<Device> findByUserIdOrderByRegisteredAtDesc(String userId);

    @Query("SELECT d FROM Device d WHERE d.userId = :userId AND d.revokedAt IS NULL")
    List<Device> findActiveDevicesByUserId(String userId);

    Optional<Device> findByIdAndUserId(String id, String userId);
}

