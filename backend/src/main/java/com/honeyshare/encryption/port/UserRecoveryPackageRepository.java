package com.honeyshare.encryption.port;

import com.honeyshare.encryption.domain.UserRecoveryPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRecoveryPackageRepository extends JpaRepository<UserRecoveryPackage, String> {

    Optional<UserRecoveryPackage> findByUserId(String userId);
}

