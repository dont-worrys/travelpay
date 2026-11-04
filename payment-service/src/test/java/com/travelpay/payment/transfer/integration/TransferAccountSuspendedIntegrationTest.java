package com.travelpay.payment.transfer.integration;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-22: 원장 계정 정지(LEDGER_REJECTED_WALLET_SUSPENDED)·계정 없음 거절과 지갑 상태 사전 확인을 검증합니다.
 * 원장 Feign 응답(422 + 오류 코드)만 Mock이고, 어댑터의 오류 코드 해석부터 DB 기록·API 응답까지는 실제 코드입니다.
 */
class TransferAccountSuspendedIntegrationTest extends TransferApiIntegrationSupport {

    @Autowired
    private TransferService transferService;

    /** TC-22-I1·I2: 원장 계정 정지·계정 없음 → 422 TRANSFER_ACCOUNT_UNAVAILABLE, FAILED + ACCOUNT_UNAVAILABLE로 기록합니다. */
    @ParameterizedTest
    @ValueSource(strings = {"LEDGER_REJECTED_WALLET_SUSPENDED", "LEDGER_REJECTED_ACCOUNT_NOT_FOUND"})
    void recordsFailedWithAccountUnavailableWhenLedgerRejectsAccount(String ledgerCode) throws Exception {
        when(ledgerFeignApi.register(any())).thenThrow(ledgerError(422, ledgerCode));

        requestTransfer("tc22-" + ledgerCode)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_ACCOUNT_UNAVAILABLE"));

        UUID transferId = onlyTransferIdOf(senderId);
        Map<String, Object> row = row(transferId);
        assertEquals("FAILED", row.get("status"));
        assertEquals("ACCOUNT_UNAVAILABLE", row.get("failure_reason"));
        assertEquals(0, ((Number) row.get("attempt_count")).intValue());
        assertNull(row.get("review_reason"));
        assertEquals(sourceWalletId, row.get("source_wallet_id"));
        assertEquals(targetWalletId, row.get("target_wallet_id"));
        verify(ledgerFeignApi, times(1)).register(any());
    }

    /** TC-22-I3·I4·I5·I6: 거절된 송금은 같은 키 재요청에 같은 422(원장 미호출), 복구 대상 아님, 상세는 FAILED, 취소는 409입니다. */
    @Test
    void failedTransferIsFinalForRetryRecoveryDetailAndCancel() throws Exception {
        assumeTrue(countByStatus("PROCESSING") == 0, "DB에 다른 PROCESSING 송금이 없어야 합니다.");
        when(ledgerFeignApi.register(any())).thenThrow(ledgerError(422, "LEDGER_REJECTED_WALLET_SUSPENDED"));
        requestTransfer("tc22-final").andExpect(status().isUnprocessableEntity());
        UUID transferId = onlyTransferIdOf(senderId);

        requestTransfer("tc22-final")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_ACCOUNT_UNAVAILABLE"));
        transferService.recoverDue(Instant.now().plusSeconds(86_400), 10);
        verify(ledgerFeignApi, times(1)).register(any());

        mvc.perform(asUser(get("/api/v1/transfers/{id}", transferId), senderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        mvc.perform(asUser(post("/api/v1/transfers/{id}/cancel", transferId), senderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("COMMON_CONFLICT"));
        assertEquals("FAILED", row(transferId).get("status"));
        assertEquals(1L, countTransfersOf(senderId));
    }

    /** TC-22-I7: 송금인 지갑이 SUSPENDED면 원장 호출·송금 기록 없이 422 TRANSFER_SENDER_WALLET_UNAVAILABLE입니다. */
    @Test
    void rejectsBeforeLedgerWhenSenderWalletIsSuspended() throws Exception {
        givenWallet(senderId, sourceWalletId, "SUSPENDED");

        requestTransfer("tc22-sender-wallet")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_SENDER_WALLET_UNAVAILABLE"));

        assertEquals(0L, countTransfersOf(senderId));
        verify(ledgerFeignApi, never()).register(any());
    }

    /** TC-22-I8: 수취인 지갑이 SUSPENDED면 422 TRANSFER_RECEIVER_UNAVAILABLE, 수취인 조회 API는 transferable=false입니다. */
    @Test
    void rejectsBeforeLedgerWhenReceiverWalletIsSuspended() throws Exception {
        givenWallet(receiverId, targetWalletId, "SUSPENDED");

        requestTransfer("tc22-receiver-wallet")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_RECEIVER_UNAVAILABLE"));
        mvc.perform(asUser(get("/api/v1/transfers/recipients/{id}", receiverId), senderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transferable").value(false))
                .andExpect(jsonPath("$.data.receiverName").value("홍*동"));

        assertEquals(0L, countTransfersOf(senderId));
        verify(ledgerFeignApi, never()).register(any());
    }

    /** TC-22-I9: 원장이 모르는 422 코드는 계정 거절로 단정하지 않고 MANUAL_REVIEW(LEDGER_REQUEST_NOT_ACCEPTED)로 둡니다. */
    @Test
    void doesNotTreatUnknownLedgerRejectionAsAccountSuspension() throws Exception {
        when(ledgerFeignApi.register(any())).thenThrow(ledgerError(422, "LEDGER_REJECTED_SOMETHING_NEW"));

        requestTransfer("tc22-unknown-422")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"));

        Map<String, Object> row = row(onlyTransferIdOf(senderId));
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertEquals("LEDGER_REQUEST_NOT_ACCEPTED", row.get("review_reason"));
        assertNull(row.get("failure_reason"));
    }

    /** TC-22-I10: 처리 중 송금을 복구하다 원장이 계정 정지로 거절하면 FAILED + ACCOUNT_UNAVAILABLE로 확정합니다. */
    @Test
    void recoveryMarksProcessingTransferFailedWhenLedgerRejectsAccount() throws Exception {
        assumeTrue(countByStatus("PROCESSING") == 0, "DB에 다른 PROCESSING 송금이 없어야 합니다.");
        when(ledgerFeignApi.register(any()))
                .thenThrow(ledgerError(503, "COMMON_SERVICE_UNAVAILABLE"))
                .thenThrow(ledgerError(422, "LEDGER_REJECTED_WALLET_SUSPENDED"));
        UUID transferId = transferIdOf(requestTransfer("tc22-recovery").andExpect(status().isAccepted()));
        assertEquals("PROCESSING", row(transferId).get("status"));

        int settled = transferService.recoverDue(updatedAt(transferId).plusSeconds(31), 10);

        assertEquals(1, settled);
        Map<String, Object> row = row(transferId);
        assertEquals("FAILED", row.get("status"));
        assertEquals("ACCOUNT_UNAVAILABLE", row.get("failure_reason"));
        assertEquals(1, ((Number) row.get("attempt_count")).intValue());
        requestTransfer("tc22-recovery")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_ACCOUNT_UNAVAILABLE"));
    }

    private UUID onlyTransferIdOf(UUID userId) {
        return jdbcTemplate.queryForObject("select id from transfer where sender_id = ?", UUID.class, userId);
    }
}
