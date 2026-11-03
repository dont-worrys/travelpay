package com.travelpay.payment.transfer.integration;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.transfer.application.TransferService;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import feign.FeignException;
import feign.Request;
import feign.RetryableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-08: 원장 장애(연결 실패·Eureka 미등록·5xx·타임아웃·해석 불가 응답) 중 송금을 실패로 단정하지 않고 PROCESSING(202)으로 남긴 뒤,
 * 원장이 돌아오면 복구가 같은 reference로 확정하는지 검증합니다.
 * 원장 Feign 호출 결과만 Mock이고 TransferLedgerClientAdapter의 장애 분류부터 DB·API 응답까지는 실제 코드입니다.
 */
@EnabledIfEnvironmentVariable(named = "PAYMENT_DB_URL", matches = ".+")
class TransferLedgerOutageIntegrationTest extends TransferApiIntegrationSupport {

    private static final String LEDGER_URL = "http://ledger-service/internal/v1/ledger/transactions";

    @Autowired
    private TransferService transferService;

    @BeforeEach
    void requireNoOtherProcessing() {
        // 복구는 DB의 모든 처리 중 송금을 다루므로, 다른 처리 중 송금이 있으면 결과가 섞여 실행하지 않습니다.
        assumeTrue(countByStatus("PROCESSING") == 0, "로컬 DB에 PROCESSING 송금이 없어야 합니다.");
    }

    /** TC-08-I1~I6: 장애 종류별로 202 PROCESSING, 시도 1, 실패 사유 없음. 장애 내용은 응답에 노출하지 않습니다. */
    @ParameterizedTest
    @ValueSource(strings = {"CONNECT_REFUSED", "READ_TIMEOUT", "NO_INSTANCE_503", "HTTP_500", "HTTP_502", "HTTP_504",
            "UNREADABLE_2XX"})
    void keepsProcessingDuringLedgerOutage(String outage) throws Exception {
        givenLedgerOutage(outage);

        UUID transferId = transferIdOf(requestTransfer("tc08-" + outage)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"))
                .andExpect(content().string(not(containsString("refused"))))
                .andExpect(content().string(not(containsString("timed out")))));

        Map<String, Object> row = row(transferId);
        assertEquals("PROCESSING", row.get("status"));
        assertEquals(1, ((Number) row.get("attempt_count")).intValue());
        assertNull(row.get("failure_reason"));
        assertNull(row.get("review_reason"));
    }

    /** TC-08-I7: 장애 중 같은 키 재요청·상세 조회는 PROCESSING이고 원장을 다시 부르지 않으며, 취소는 409입니다. */
    @Test
    void retryDetailAndCancelDuringOutage() throws Exception {
        givenLedgerOutage("CONNECT_REFUSED");
        UUID transferId = transferIdOf(requestTransfer("tc08-during").andExpect(status().isAccepted()));

        requestTransfer("tc08-during")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(asUser(get("/api/v1/transfers/{id}", transferId), senderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));
        mvc.perform(asUser(post("/api/v1/transfers/{id}/cancel", transferId), senderId))
                .andExpect(status().isConflict());

        verify(ledgerFeignClient, times(1)).register(any());
        assertEquals(1, ((Number) row(transferId).get("attempt_count")).intValue());
        assertEquals(1L, countTransfersOf(senderId));
    }

    /** TC-08-I8: 원장이 돌아오면 복구가 같은 reference로 재전송해 COMPLETED로 확정하고, 재요청은 201 COMPLETED입니다. */
    @Test
    void recoversToCompletedAfterLedgerComesBack() throws Exception {
        givenLedgerOutage("NO_INSTANCE_503");
        UUID transferId = transferIdOf(requestTransfer("tc08-back").andExpect(status().isAccepted()));
        doReturn(posted(UUID.randomUUID())).when(ledgerFeignClient).register(any());

        int settled = transferService.recoverDue(updatedAt(transferId).plusSeconds(31), 10);

        assertEquals(1, settled);
        Map<String, Object> row = row(transferId);
        assertEquals("COMPLETED", row.get("status"));
        assertEquals(1, ((Number) row.get("attempt_count")).intValue());
        assertNull(row.get("review_reason"));
        requestTransfer("tc08-back")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        verify(ledgerFeignClient, times(2)).register(any());
    }

    /** TC-08-I9: 장애가 이어지면 다음 대기 시간(60초)까지 기다렸다가 다시 시도하고, 그 사이 시도 횟수만 늘어납니다. */
    @Test
    void waitsLongerBetweenAttemptsWhileOutageContinues() throws Exception {
        givenLedgerOutage("HTTP_502");
        UUID transferId = transferIdOf(requestTransfer("tc08-backoff").andExpect(status().isAccepted()));

        transferService.recoverDue(updatedAt(transferId).plusSeconds(31), 10);
        assertEquals(2, ((Number) row(transferId).get("attempt_count")).intValue());
        Instant secondTriedAt = updatedAt(transferId);
        transferService.recoverDue(secondTriedAt.plusSeconds(59), 10);
        verify(ledgerFeignClient, times(2)).register(any());

        transferService.recoverDue(secondTriedAt.plusSeconds(61), 10);
        verify(ledgerFeignClient, times(3)).register(any());
        assertEquals(3, ((Number) row(transferId).get("attempt_count")).intValue());
        assertEquals("PROCESSING", row(transferId).get("status"));
    }

    private void givenLedgerOutage(String outage) {
        Request request = request(Request.HttpMethod.POST, LEDGER_URL);
        switch (outage) {
            case "CONNECT_REFUSED" -> doThrow(new RetryableException(-1, "Connection refused", Request.HttpMethod.POST,
                    new ConnectException("Connection refused"), (Long) null, request))
                    .when(ledgerFeignClient).register(any());
            case "READ_TIMEOUT" -> doThrow(new RetryableException(-1, "Read timed out", Request.HttpMethod.POST,
                    new SocketTimeoutException("Read timed out"), (Long) null, request))
                    .when(ledgerFeignClient).register(any());
            case "NO_INSTANCE_503" -> doThrow(error(503,
                    "Load balancer does not contain an instance for the service ledger-service"))
                    .when(ledgerFeignClient).register(any());
            case "HTTP_500" -> doThrow(ledgerError(500, "COMMON_INTERNAL_SERVER_ERROR"))
                    .when(ledgerFeignClient).register(any());
            case "HTTP_502" -> doThrow(ledgerError(502, "COMMON_BAD_GATEWAY")).when(ledgerFeignClient).register(any());
            case "HTTP_504" -> doThrow(ledgerError(504, "COMMON_GATEWAY_TIMEOUT"))
                    .when(ledgerFeignClient).register(any());
            case "UNREADABLE_2XX" -> doReturn(ApiResponse.success(null)).when(ledgerFeignClient).register(any());
            default -> throw new IllegalArgumentException(outage);
        }
    }

    private static FeignException error(int status, String body) {
        return feignError("TransferLedgerFeignClient#register(LedgerTransactionRequest)", Request.HttpMethod.POST,
                LEDGER_URL, status, body);
    }
}
