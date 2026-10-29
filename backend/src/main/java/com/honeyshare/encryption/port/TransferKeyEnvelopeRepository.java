package com.honeyshare.encryption.port;

import com.honeyshare.encryption.domain.TransferKeyEnvelope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransferKeyEnvelopeRepository extends JpaRepository<TransferKeyEnvelope, String> {

    List<TransferKeyEnvelope> findByTransferId(String transferId);

    Optional<TransferKeyEnvelope> findByTransferIdAndRecipientDeviceId(String transferId, String recipientDeviceId);

    boolean existsByTransferIdAndRecipientDeviceId(String transferId, String recipientDeviceId);
}

