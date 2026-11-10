package com.travelpay.payment.transfer.integration;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.payment.transfer.application.TransferRecoveryScheduler;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-10: 복구 스케줄러의 한 주기(recover)가 실제 DB의 처리 중 송금을 원장에 다시 보내 확정하는지 검증합니다.
 * 백그라운드 스케줄 스레드는 테스트 트랜잭션의 데이터를 볼 수 없어 끄고, 같은 스케줄러 클래스를 실제 TransferService로 만들어
 * recover()를 직접 호출합니다. 실제 @Scheduled 스레드 동작은 E2E(서버 기동) 범위입니다.
 */
class TransferRecoverySchedulerIntegrationTest extends TransferApiIntegrationSupport {

    @Autowired
    private TransferService transferService;

    private SimpleMeterRegistry meterRegistry;
    private TransferRecoveryScheduler scheduler;

    @BeforeEach
    void setUpScheduler() {
        // 복구는 DB의 모든 처리 중 송금을 다루므로, 다른 처리 중 송금이 있으면 결과가 섞여 실행하지 않습니다.
        assumeTrue(countByStatus("PROCESSING") == 0, "DB에 다른 PROCESSING 송금이 없어야 합니다.");
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new TransferRecoveryScheduler(transferService, meterRegistry);
    }

    /** TC-10-I1: 결과 모름으로 남은 송금을 재요청 없이 스케줄러가 같은 reference로 재전송해 COMPLETED로 확정합니다. */
    @Test
    void recoversProcessingTransferToCompletedWithoutClientRetry() throws Exception {
        UUID transferId = processingTransfer("tc10-complete");
        makeDue(transferId);
        UUID ledgerTransactionId = UUID.randomUUID();
        doReturn(posted(ledgerTransactionId)).when(ledgerFeignApi).register(any());

        scheduler.recover();

        Map<String, Object> row = row(transferId);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(1, ((Number) row.get("attempt_count")).intValue());
        assertNull(row.get("failure_reason"));
        assertNull(row.get("review_reason"));
        ArgumentCaptor<LedgerFeignApi.RegisterRequest> sent = ArgumentCaptor.forClass(LedgerFeignApi.RegisterRequest.class);
        verify(ledgerFeignApi, times(2)).register(sent.capture());
        assertEquals(sent.getAllValues().get(0), sent.getAllValues().get(1));
        assertEquals(transferId, sent.getValue().referenceId());
        assertEquals("TRANSFER", sent.getValue().referenceType());

        requestTransfer("tc10-complete")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        verify(ledgerFeignApi, times(2)).register(any());
    }

    /** TC-10-I2: 재시도 대기 시간(30초)이 지나지 않은 송금은 다루지 않습니다. */
    @Test
    void skipsTransferBeforeRetryWait() throws Exception {
        UUID transferId = processingTransfer("tc10-not-due");
        clearInvocations(ledgerFeignApi);

        scheduler.recover();

        verify(ledgerFeignApi, never()).register(any());
        assertEquals("PROCESSING", row(transferId).get("status"));
    }

    /** TC-10-I3: 한 주기에 오래된 순으로 최대 10건만 처리하고, 남은 건은 다음 주기에 처리합니다. */
    @Test
    void processesAtMostTenOldestTransfersPerCycle() throws Exception {
        Instant base = Instant.now().minusSeconds(3_600);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID id = processingTransfer("tc10-batch-" + i);
            setTimes(id, base, base.plusSeconds(i));
            ids.add(id);
        }
        doReturn(posted(UUID.randomUUID())).when(ledgerFeignApi).register(any());

        scheduler.recover();

        for (int i = 0; i < 12; i++) {
            assertEquals(i < 10 ? "COMPLETED" : "PROCESSING", row(ids.get(i)).get("status"), "index " + i);
        }
        scheduler.recover();
        ids.forEach(id -> assertEquals("COMPLETED", row(id).get("status")));
    }

    /** TC-10-I4: 원장이 계속 응답하지 못하면 한 주기에 3건만 시도하고 멈추며, 시도한 송금만 횟수가 늘어납니다. */
    @Test
    void stopsCycleAfterThreeConsecutiveUnknownResults() throws Exception {
        Instant base = Instant.now().minusSeconds(3_600);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID id = processingTransfer("tc10-outage-" + i);
            setTimes(id, base, base.plusSeconds(i));
            ids.add(id);
        }
        clearInvocations(ledgerFeignApi);

        assertDoesNotThrow(scheduler::recover);

        verify(ledgerFeignApi, times(3)).register(any());
        for (int i = 0; i < 5; i++) {
            assertEquals("PROCESSING", row(ids.get(i)).get("status"));
            assertEquals(i < 3 ? 2 : 1, ((Number) row(ids.get(i)).get("attempt_count")).intValue(), "index " + i);
        }
    }

    /** TC-10-I5: 30분 넘게 확정되지 않은 송금 수와 MANUAL_REVIEW 수를 게이지로 기록합니다. */
    @Test
    void publishesStuckAndManualReviewGaugesFromDatabase() throws Exception {
        UUID stuck = processingTransfer("tc10-stuck");
        Instant now = Instant.now();
        // 생성은 31분 전, 마지막 시도는 방금(대기 중)이라 이번 주기에 재전송하지 않습니다.
        setTimes(stuck, now.minusSeconds(31 * 60), now);

        scheduler.recover();

        assertEquals(1.0, meterRegistry.get("transfer.processing.stuck").gauge().value());
        assertEquals((double) countByStatus("MANUAL_REVIEW"),
                meterRegistry.get("transfer.manual_review").gauge().value());
        assertEquals("PROCESSING", row(stuck).get("status"));
    }

    /** TC-10-U1: 스케줄 설정(10초 간격, 기본 활성, transfer.recovery.enabled=false로 끔)과 배치 크기를 확인합니다. */
    @Test
    void schedulerConfiguration() throws Exception {
        Method recover = TransferRecoveryScheduler.class.getMethod("recover");
        Scheduled scheduled = recover.getAnnotation(Scheduled.class);
        assertEquals(10_000L, scheduled.fixedDelay());
        assertEquals(10_000L, scheduled.initialDelay());
        ConditionalOnProperty condition = TransferRecoveryScheduler.class.getAnnotation(ConditionalOnProperty.class);
        assertEquals("transfer.recovery", condition.prefix());
        assertArrayEquals(new String[] {"enabled"}, condition.name());
        assertTrue(condition.matchIfMissing());
        // 이 테스트 구성은 transfer.recovery.enabled=false라 스케줄러 빈이 없어야 합니다.
        assertTrue(context.getBeansOfType(TransferRecoveryScheduler.class).isEmpty());
    }

    /** 원장 503으로 결과 모름(PROCESSING, 시도 1)인 송금을 API로 만듭니다. */
    private UUID processingTransfer(String key) throws Exception {
        doThrow(ledgerError(503, "COMMON_SERVICE_UNAVAILABLE")).when(ledgerFeignApi).register(any());
        UUID id = transferIdOf(requestTransfer(key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PROCESSING")));
        assertEquals(1, ((Number) row(id).get("attempt_count")).intValue());
        return id;
    }

    private void makeDue(UUID transferId) {
        Instant past = Instant.now().minusSeconds(60);
        setTimes(transferId, past, past);
    }
}
