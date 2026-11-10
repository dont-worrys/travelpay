package com.travelpay.payment.transfer.integration;

import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.infra.client.user.UserFeignApi;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-24: user-service 수취인 실명 조회(I02)의 호출 제한 초과(429 COMMON_TOO_MANY_REQUESTS)를 송금 API가 어떻게 전달하는지 검증합니다.
 * 송금 API 자체의 호출 제한은 구현되어 있지 않으며(Gateway·payment 모두 없음), 이 테스트의 대상이 아닙니다.
 * UserFeignApi(Feign) 응답만 Mock이고 TransferUserReader·common FeignErrors·서비스·컨트롤러·DB는 실제 코드입니다.
 */
class TransferRateLimitIntegrationTest extends TransferApiIntegrationSupport {

    /** TC-24-I1: 수취인 조회 중 429 → 429 COMMON_TOO_MANY_REQUESTS, 지갑은 조회하지 않습니다. Retry-After 헤더는 전달하지 않습니다. */
    @Test
    void recipientLookupReturnsTooManyRequests() throws Exception {
        doThrow(userError(429, errorBody("COMMON_TOO_MANY_REQUESTS"))).when(userFeignApi).getActiveUser(receiverId);

        mvc.perform(asUser(get("/api/v1/transfers/recipients/{id}", receiverId), senderId))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_TOO_MANY_REQUESTS"))
                .andExpect(header().doesNotExist("Retry-After"));

        verify(walletFeignApi, never()).getWallet(receiverId);
    }

    /** TC-24-I2: 송금 요청 중 429 → 429, 송금은 기록되지 않고 원장도 호출하지 않습니다. */
    @Test
    void transferRequestReturnsTooManyRequestsWithoutSaving() throws Exception {
        doThrow(userError(429, errorBody("COMMON_TOO_MANY_REQUESTS"))).when(userFeignApi).getActiveUser(receiverId);

        requestTransfer("tc24-limited")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("COMMON_TOO_MANY_REQUESTS"));

        assertEquals(0L, countTransfersOf(senderId));
        verify(ledgerFeignApi, never()).register(any());
        verify(walletFeignApi, never()).getWallet(receiverId);
    }

    /** TC-24-I3: 429로 거절된 요청은 멱등성 키를 쓰지 않아, 제한이 풀린 뒤 같은 키로 다시 보내면 정상 처리됩니다. */
    @Test
    void sameKeySucceedsAfterLimitIsLifted() throws Exception {
        doThrow(userError(429, errorBody("COMMON_TOO_MANY_REQUESTS"))).when(userFeignApi).getActiveUser(receiverId);
        requestTransfer("tc24-retry").andExpect(status().isTooManyRequests());

        doReturn(ApiResponse.success(new UserFeignApi.UserResponse(receiverId, "홍길동")))
                .when(userFeignApi).getActiveUser(receiverId);
        doReturn(posted(UUID.randomUUID())).when(ledgerFeignApi).register(any());

        requestTransfer("tc24-retry")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        assertEquals(1L, countTransfersOf(senderId));
    }

    /** TC-24-I4: 429라도 오류 코드가 COMMON_TOO_MANY_REQUESTS가 아니면(프록시 HTML 등) 호출 제한으로 보지 않고 502입니다. */
    @Test
    void tooManyRequestsWithoutCommonCodeIsBadGateway() throws Exception {
        doThrow(userError(429, "<html>Too Many Requests</html>")).when(userFeignApi).getActiveUser(receiverId);

        mvc.perform(asUser(get("/api/v1/transfers/recipients/{id}", receiverId), senderId))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("COMMON_BAD_GATEWAY"));
        requestTransfer("tc24-html")
                .andExpect(status().isBadGateway());
        assertEquals(0L, countTransfersOf(senderId));
    }

    /** TC-24-I5: 같은 코드라도 상태가 429가 아니면(400) 호출 제한으로 보지 않고 502입니다. */
    @Test
    void commonCodeWithOtherStatusIsBadGateway() throws Exception {
        doThrow(userError(400, errorBody("COMMON_TOO_MANY_REQUESTS"))).when(userFeignApi).getActiveUser(receiverId);

        mvc.perform(asUser(get("/api/v1/transfers/recipients/{id}", receiverId), senderId))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("COMMON_BAD_GATEWAY"));
    }

    private FeignException userError(int status, String body) {
        return feignError("UserFeignApi#getActiveUser(UUID)", Request.HttpMethod.GET,
                "http://user-service/internal/v1/users/" + receiverId + "/real-name", status, body);
    }
}
