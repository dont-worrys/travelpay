package com.travelpay.payment.transfer.integration;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.client.LedgerTransactionRequest;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-09: 원장은 거래를 처리했지만 응답이 유실(읽기 타임아웃)된 뒤의 재시도를 검증합니다.
 * 원장 Feign 호출은 reference별 처리 결과를 기억하는 Mock 응답으로 바꿉니다. 처음 요청은 처리하고 응답만 잃으며,
 * 같은 reference의 재요청에는 처리 없이 처음 결과(200, 같은 거래 ID 또는 같은 422)를 돌려줍니다(원장 PR #37의 reference 멱등 계약).
 * 실제 원장의 멱등성은 2026-11-02 로컬 E2E(원장 직접 재전송 2회 → 같은 transactionId)로 확인된 사실을 전제로 합니다.
 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_DB_URL", matches = ".+")
class TransferLedgerResponseLossIntegrationTest extends TransferApiIntegrationSupport {

    @Autowired
    private TransferService transferService;

    private final Map<UUID, Object> processed = new HashMap<>();
    private final List<LedgerTransactionRequest> received = new ArrayList<>();
    private int applied;

    @BeforeEach
    void requireNoOtherProcessing() {
        assumeTrue(countByStatus("PROCESSING") == 0, "로컬 DB에 PROCESSING 송금이 없어야 합니다.");
    }

    /** TC-09-I1: 응답 유실 → 202 PROCESSING. 복구가 같은 reference·내용으로 재전송해 같은 거래 ID로 COMPLETED, 원장 반영은 1회입니다. */
    @Test
    void completesWithSingleLedgerApplicationAfterResponseLoss() throws Exception {
        givenLedgerThatLosesFirstResponse(null);

        UUID transferId = transferIdOf(requestTransfer("tc09-lost")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING")));
        assertEquals("PROCESSING", row(transferId).get("status"));
        assertEquals(1, applied);

        int settled = transferService.recoverDue(updatedAt(transferId).plusSeconds(31), 10);

        assertEquals(1, settled);
        assertEquals(1, applied);
        assertEquals(2, received.size());
        assertEquals(received.get(0), received.get(1));
        assertEquals(transferId, received.get(0).referenceId());
        Map<String, Object> row = row(transferId);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(1, ((Number) row.get("attempt_count")).intValue());
        assertNull(row.get("failure_reason"));
        requestTransfer("tc09-lost")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertEquals(1L, countTransfersOf(senderId));
    }

    /** TC-09-I2: 복구 전 클라이언트가 같은 키로 재시도해도 원장에 다시 보내지 않고(중복 위험 없음) 202 PROCESSING입니다. */
    @Test
    void clientRetryBeforeRecoveryDoesNotResend() throws Exception {
        givenLedgerThatLosesFirstResponse(null);
        UUID transferId = transferIdOf(requestTransfer("tc09-client-retry").andExpect(status().isAccepted()));

        for (int i = 0; i < 3; i++) {
            requestTransfer("tc09-client-retry")
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.data.transferId").value(transferId.toString()))
                    .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        }

        assertEquals(1, received.size());
        assertEquals(1, applied);
        assertEquals(1, ((Number) row(transferId).get("attempt_count")).intValue());
        assertEquals(1L, countTransfersOf(senderId));
    }

    /** TC-09-I3: 원장이 잔액 부족으로 거절했는데 응답이 유실되면, 재전송에서 같은 422를 받아 FAILED로 확정합니다. */
    @Test
    void rejectionLostInTransitIsConfirmedOnResend() throws Exception {
        givenLedgerThatLosesFirstResponse("LEDGER_REJECTED_INSUFFICIENT_BALANCE");
        UUID transferId = transferIdOf(requestTransfer("tc09-rejected").andExpect(status().isAccepted()));

        transferService.recoverDue(updatedAt(transferId).plusSeconds(31), 10);

        Map<String, Object> row = row(transferId);
        assertEquals("FAILED", row.get("status"));
        assertEquals("INSUFFICIENT_BALANCE", row.get("failure_reason"));
        assertEquals(0, applied);
        requestTransfer("tc09-rejected")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_INSUFFICIENT_BALANCE"));
    }

    /** TC-09-I4: 재전송이 409(같은 reference·다른 내용)를 받으면 FAILED로 단정하지 않고 MANUAL_REVIEW로 멈춥니다. */
    @Test
    void referenceConflictOnResendStopsForManualReview() throws Exception {
        givenLedgerThatLosesFirstResponse(null);
        UUID transferId = transferIdOf(requestTransfer("tc09-conflict").andExpect(status().isAccepted()));
        doAnswer(invocation -> {
            throw ledgerError(409, "LEDGER_REFERENCE_CONFLICT");
        }).when(ledgerFeignClient).register(any());

        transferService.recoverDue(updatedAt(transferId).plusSeconds(31), 10);

        Map<String, Object> row = row(transferId);
        assertEquals("MANUAL_REVIEW", row.get("status"));
        assertEquals("LEDGER_REQUEST_NOT_ACCEPTED", row.get("review_reason"));
        assertNull(row.get("failure_reason"));
        requestTransfer("tc09-conflict")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"));
    }

    /**
     * reference별로 처음 결과를 기억하는 원장 Mock입니다. 처음 요청은 처리(rejectCode가 있으면 거절)하고 응답을 잃으며(읽기 타임아웃),
     * 같은 reference의 다음 요청에는 다시 처리하지 않고 처음 결과를 돌려줍니다.
     */
    private void givenLedgerThatLosesFirstResponse(String rejectCode) {
        doAnswer(invocation -> {
            LedgerTransactionRequest request = invocation.getArgument(0);
            received.add(request);
            UUID reference = request.referenceId();
            if (!processed.containsKey(reference)) {
                if (rejectCode == null) {
                    processed.put(reference, UUID.randomUUID());
                    applied++;
                } else {
                    processed.put(reference, rejectCode);
                }
                throw new RetryableException(-1, "Read timed out", Request.HttpMethod.POST,
                        new SocketTimeoutException("Read timed out"), (Long) null,
                        request(Request.HttpMethod.POST, "http://ledger-service/internal/v1/ledger/transactions"));
            }
            Object first = processed.get(reference);
            if (first instanceof UUID transactionId) {
                return posted(transactionId);
            }
            throw ledgerError(422, (String) first);
        }).when(ledgerFeignClient).register(any());
    }
}
