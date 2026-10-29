package com.honeyshare.transfers.adapter.web;

import com.honeyshare.identity.domain.User;
import com.honeyshare.identity.domain.UserRole;
import com.honeyshare.identity.domain.UserSession;
import com.honeyshare.security.AuthenticatedPrincipal;
import com.honeyshare.transfers.domain.PrivacyMode;
import com.honeyshare.transfers.domain.Transfer;
import com.honeyshare.transfers.port.TransferRepository;
import com.honeyshare.transfers.service.TransferService;
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
@DisplayName("TransferController Unit Tests")
class TransferControllerTest {

    @Mock
    private TransferService transferService;

    @Mock
    private TransferRepository transferRepository;

    private TransferController controller;
    private User testUser;
    private UserSession testSession;

    @BeforeEach
    void setUp() {
        controller = new TransferController(transferService, transferRepository);

        testUser = User.create("sender@example.com", "hash", UserRole.USER);
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
    @DisplayName("POST /transfers creates transfer and returns 201 Created")
    void createTransfer_returnsCreated() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");

        Transfer transfer = Transfer.create(
                testUser.getId(), "report.pdf", 2048L, "application/pdf",
                PrivacyMode.STANDARD, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        when(transferService.createTransfer(
                eq(testUser.getId()), eq("report.pdf"), eq(2048L), eq("application/pdf"),
                eq(PrivacyMode.STANDARD), eq("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                eq(List.of("bob@example.com")), eq("192.168.1.100")
        )).thenReturn(transfer);

        TransferController.CreateTransferRequest body = new TransferController.CreateTransferRequest(
                "report.pdf", 2048L, "application/pdf", PrivacyMode.STANDARD,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", List.of("bob@example.com"));

        ResponseEntity<Map<String, Object>> response = controller.createTransfer(body, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("transferId", transfer.getId());
        assertThat(response.getBody()).containsEntry("state", "CREATED");
    }

    @Test
    @DisplayName("GET /transfers/sent returns list of user sent transfers")
    void getSentTransfers_returnsList() {
        Transfer transfer = Transfer.create(
                testUser.getId(), "file.zip", 4096L, "application/zip",
                PrivacyMode.STANDARD, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");

        when(transferRepository.findBySenderIdOrderByCreatedAtDesc(testUser.getId())).thenReturn(List.of(transfer));

        ResponseEntity<List<TransferController.TransferSummaryDto>> response = controller.getSentTransfers();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(1);
        assertThat(response.getBody().get(0).transferId()).isEqualTo(transfer.getId());
        assertThat(response.getBody().get(0).originalFilename()).isEqualTo("file.zip");
    }

    @Test
    @DisplayName("POST /transfers/{id}/cancel cancels transfer")
    void cancelTransfer_returnsOk() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.168.1.100");

        ResponseEntity<Map<String, String>> response = controller.cancelTransfer("trans-123", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("message", "Transfer cancelled");
        verify(transferService).cancelTransfer("trans-123", testUser.getId(), "192.168.1.100");
    }

    @Test
    @DisplayName("POST /transfers/{id}/sender-deletion-receipt returns durable storage receipt")
    void getSenderDeletionReceipt_returnsReceipt() {
        TransferService.SenderDeletionReceipt receipt = new TransferService.SenderDeletionReceipt(
                "trans-123", "report.pdf", 2048L, "sha-hex", "s3-key", Instant.now(),
                "receipt-token-abc", true,
                "HoneyShare Cryptographic Storage Confirmation: Object trans-123 is durably verified."
        );
        when(transferService.getSenderDeletionReceipt("trans-123", testUser.getId())).thenReturn(receipt);

        ResponseEntity<TransferService.SenderDeletionReceipt> response = controller.getSenderDeletionReceipt("trans-123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(receipt);
    }
}
