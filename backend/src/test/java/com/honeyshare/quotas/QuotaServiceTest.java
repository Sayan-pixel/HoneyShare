package com.honeyshare.quotas;

import com.honeyshare.common.exception.QuotaExceededException;
import com.honeyshare.config.TransferProperties;
import com.honeyshare.transfers.domain.QuotaReservation;
import com.honeyshare.transfers.domain.QuotaReservationState;
import com.honeyshare.transfers.port.QuotaReservationRepository;
import com.honeyshare.transfers.port.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QuotaServiceTest {

    @Mock QuotaReservationRepository quotaReservationRepository;
    @Mock TransferRepository transferRepository;

    TransferProperties transferProperties;
    QuotaService quotaService;

    @BeforeEach
    void setUp() {
        transferProperties = new TransferProperties();
        quotaService = new QuotaService(quotaReservationRepository, transferRepository, transferProperties);
    }

    @Test
    void reserveQuota_withinLimit_succeeds() {
        when(quotaReservationRepository.sumActiveReservationsByUserId("user-1")).thenReturn(1000L);
        when(transferRepository.sumStoredBytesBySenderId("user-1")).thenReturn(2000L);
        when(quotaReservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        QuotaReservation res = quotaService.reserveQuota("user-1", "transfer-1", 5000L);

        assertThat(res.getReservedBytes()).isEqualTo(5000L);
        assertThat(res.getState()).isEqualTo(QuotaReservationState.ACTIVE);
        verify(quotaReservationRepository).save(res);
    }

    @Test
    void reserveQuota_exceedingTotalUserLimit_throwsQuotaExceededException() {
        long userLimit = transferProperties.getQuota().getDefaultUserBytes();
        when(quotaReservationRepository.sumActiveReservationsByUserId("user-1")).thenReturn(userLimit - 1000L);
        when(transferRepository.sumStoredBytesBySenderId("user-1")).thenReturn(500L);

        // Requested 1000 bytes, but only 500 bytes remaining
        assertThatThrownBy(() -> quotaService.reserveQuota("user-1", "transfer-1", 1000L))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("Quota exceeded");

        verify(quotaReservationRepository, never()).save(any());
    }

    @Test
    void reserveQuota_exceedingMaxSingleFileSize_throwsQuotaExceededException() {
        long maxFileSize = transferProperties.getUpload().getMaxFileSizeBytes();

        assertThatThrownBy(() -> quotaService.reserveQuota("user-1", "transfer-1", maxFileSize + 1L))
                .isInstanceOf(QuotaExceededException.class)
                .hasMessageContaining("exceeds maximum individual limit");
    }

    @Test
    void commitReservation_marksCommitted() {
        QuotaReservation res = QuotaReservation.create("user-1", "transfer-1", 5000L);
        when(quotaReservationRepository.findByTransferIdAndState("transfer-1", QuotaReservationState.ACTIVE))
                .thenReturn(Optional.of(res));

        quotaService.commitReservation("transfer-1");

        assertThat(res.getState()).isEqualTo(QuotaReservationState.COMMITTED);
        verify(quotaReservationRepository).save(res);
    }

    @Test
    void releaseReservation_marksReleased() {
        QuotaReservation res = QuotaReservation.create("user-1", "transfer-1", 5000L);
        when(quotaReservationRepository.findByTransferIdAndState("transfer-1", QuotaReservationState.ACTIVE))
                .thenReturn(Optional.of(res));

        quotaService.releaseReservation("transfer-1");

        assertThat(res.getState()).isEqualTo(QuotaReservationState.RELEASED);
        assertThat(res.getReleasedAt()).isNotNull();
        verify(quotaReservationRepository).save(res);
    }
}

