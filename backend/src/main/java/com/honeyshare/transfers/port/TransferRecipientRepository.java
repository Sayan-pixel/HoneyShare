package com.honeyshare.transfers.port;

import com.honeyshare.transfers.domain.TransferRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TransferRecipientRepository extends JpaRepository<TransferRecipient, String> {

    List<TransferRecipient> findByTransferId(String transferId);

    List<TransferRecipient> findByRecipientUserIdOrderByCreatedAtDesc(String recipientUserId);

    Optional<TransferRecipient> findByTransferIdAndRecipientUserId(String transferId, String recipientUserId);

    boolean existsByTransferIdAndRecipientUserId(String transferId, String recipientUserId);
}

