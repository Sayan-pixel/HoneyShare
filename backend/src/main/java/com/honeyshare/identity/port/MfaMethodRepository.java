package com.honeyshare.identity.port;

import com.honeyshare.identity.domain.MfaMethod;
import com.honeyshare.identity.domain.MfaMethodType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MfaMethodRepository extends JpaRepository<MfaMethod, String> {

    List<MfaMethod> findByUserId(String userId);

    Optional<MfaMethod> findFirstByUserIdAndMethodTypeAndEnabledAtIsNotNull(
            String userId, MfaMethodType type);

    List<MfaMethod> findByUserIdAndMethodTypeAndUsedAtIsNull(
            String userId, MfaMethodType type);

    boolean existsByUserIdAndMethodTypeAndEnabledAtIsNotNull(String userId, MfaMethodType type);
}

