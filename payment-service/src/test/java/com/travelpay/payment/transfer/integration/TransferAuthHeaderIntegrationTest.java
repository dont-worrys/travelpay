package com.travelpay.payment.transfer.integration;

import java.util.Map;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.payment.transfer.controller.TransferController;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-26(payment-service 쪽): Gateway가 세션 검증 후 붙이는 User-Id·User-Role 헤더를 송금 API가 어떻게 해석하고,
 * 그 회원 범위로만 동작하는지 실제 MVC 구성(common CurrentUserHeaderInterceptor 포함)과 DB로 검증합니다.
 * 세션 검증·브라우저가 보낸 회원 헤더 제거는 Gateway(인프라 담당) 책임이며 이 테스트의 대상이 아닙니다.
 */
class TransferAuthHeaderIntegrationTest extends TransferApiIntegrationSupport {

    /** TC-26-I1: 회원 헤더가 없으면 5개 API 모두 401 COMMON_UNAUTHORIZED이고 다른 서비스를 호출하지 않습니다. */
    @ParameterizedTest
    @ValueSource(strings = {"LIST", "DETAIL", "CREATE", "CANCEL", "RECIPIENT"})
    void everyEndpointRequiresMemberHeaders(String endpoint) throws Exception {
        mvc.perform(endpoint(endpoint))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));

        verifyNoInteractions(walletFeignApi, userFeignApi, ledgerFeignApi);
        assertEquals(0L, countTransfersOf(senderId));
    }

    /** TC-26-I2: 헤더 하나만 있거나 형식이 잘못되면(축약 UUID·없는 역할·빈 값) 401입니다. */
    @ParameterizedTest
    @CsvSource(value = {
            "VALID, NULL",
            "NULL, USER",
            "not-a-uuid, USER",
            "1-1-1-1-1, USER",
            "VALID, ADMIN",
            "VALID, user",
            "' ', USER"
    }, nullValues = "NULL")
    void rejectsIncompleteOrMalformedMemberHeaders(String userId, String role) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/transfers");
        if (userId != null) {
            request.header(AuthHeaders.USER_ID, "VALID".equals(userId) ? senderId.toString() : userId);
        }
        if (role != null) {
            request.header(AuthHeaders.USER_ROLE, role);
        }

        mvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
    }

    /** TC-26-I3: 같은 회원 헤더가 두 번 오면(서로 다른 회원) 어느 쪽도 믿지 않고 401입니다. */
    @Test
    void rejectsDuplicatedMemberHeader() throws Exception {
        mvc.perform(get("/api/v1/transfers")
                        .header(AuthHeaders.USER_ID, senderId.toString(), UUID.randomUUID().toString())
                        .header(AuthHeaders.USER_ROLE, "USER"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("COMMON_UNAUTHORIZED"));
    }

    /** TC-26-I4: 본문에 다른 송금인 ID를 넣어도 무시하고, 헤더의 회원을 송금인으로 기록합니다. */
    @Test
    void senderComesFromHeaderNotFromBody() throws Exception {
        UUID spoofed = UUID.randomUUID();
        doReturn(posted(UUID.randomUUID())).when(ledgerFeignApi).register(any());

        UUID transferId = transferIdOf(mvc.perform(asUser(post("/api/v1/transfers"), senderId)
                        .header(TransferController.IDEMPOTENCY_KEY_HEADER, "tc26-spoof")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"senderId":"%s","userId":"%s","receiverId":"%s","amount":3000,"currency":"KRW"}"""
                                .formatted(spoofed, spoofed, receiverId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.senderId").value(senderId.toString())));

        Map<String, Object> row = row(transferId);
        assertEquals(senderId, row.get("sender_id"));
        assertEquals(receiverId, row.get("receiver_id"));
        assertEquals(0L, countTransfersOf(spoofed));
        verify(walletFeignApi, never()).getWallet(spoofed);
    }

    /** TC-26-I5: 상세는 송금인·수취인만(각자 기준 direction), 제3자는 404. 취소는 송금인만이며 수취인·제3자는 404입니다. */
    @Test
    void transferIsScopedToItsParticipants() throws Exception {
        doReturn(posted(UUID.randomUUID())).when(ledgerFeignApi).register(any());
        UUID transferId = transferIdOf(requestTransfer("tc26-scope").andExpect(status().isCreated()));
        UUID stranger = UUID.randomUUID();

        mvc.perform(asUser(get("/api/v1/transfers/{id}", transferId), senderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direction").value("SENT"));
        mvc.perform(asUser(get("/api/v1/transfers/{id}", transferId), receiverId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.direction").value("RECEIVED"));
        mvc.perform(asUser(get("/api/v1/transfers/{id}", transferId), stranger))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
        mvc.perform(asUser(get("/api/v1/transfers/{id}", UUID.randomUUID()), senderId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
        for (UUID notSender : new UUID[] {receiverId, stranger}) {
            mvc.perform(asUser(post("/api/v1/transfers/{id}/cancel", transferId), notSender))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
        }
        assertEquals("COMPLETED", row(transferId).get("status"));
    }

    /** TC-26-I6: 헤더의 회원이 자기 자신에게 송금하거나 자신을 수취인으로 조회하면 400 TRANSFER_SELF_TRANSFER입니다. */
    @Test
    void rejectsSelfTransferByHeaderIdentity() throws Exception {
        mvc.perform(asUser(post("/api/v1/transfers"), senderId)
                        .header(TransferController.IDEMPOTENCY_KEY_HEADER, "tc26-self")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.formatted(senderId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_SELF_TRANSFER"));
        mvc.perform(asUser(get("/api/v1/transfers/recipients/{id}", senderId), senderId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_SELF_TRANSFER"));
        assertEquals(0L, countTransfersOf(senderId));
    }

    /**
     * TC-26-I7: 사용자 송금 API 5개는 MASTER면 403 COMMON_FORBIDDEN이고(wallet-service 회원 API와 같음),
     * 다른 서비스를 호출하거나 송금을 남기지 않습니다. 관리자 송금 API의 MASTER 허용은 AdminTransferApiIntegrationTest에서 확인합니다.
     */
    @ParameterizedTest
    @ValueSource(strings = {"LIST", "DETAIL", "CREATE", "CANCEL", "RECIPIENT"})
    void masterRoleIsForbiddenOnUserTransferApi(String endpoint) throws Exception {
        mvc.perform(endpoint(endpoint)
                        .header(AuthHeaders.USER_ID, senderId.toString())
                        .header(AuthHeaders.USER_ROLE, "MASTER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(walletFeignApi, userFeignApi, ledgerFeignApi);
        assertEquals(0L, countTransfersOf(senderId));
    }

    private MockHttpServletRequestBuilder endpoint(String name) {
        UUID id = UUID.randomUUID();
        return switch (name) {
            case "LIST" -> get("/api/v1/transfers");
            case "DETAIL" -> get("/api/v1/transfers/{id}", id);
            case "CREATE" -> post("/api/v1/transfers")
                    .header(TransferController.IDEMPOTENCY_KEY_HEADER, "tc26-noauth")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY.formatted(receiverId));
            case "CANCEL" -> post("/api/v1/transfers/{id}/cancel", id);
            case "RECIPIENT" -> get("/api/v1/transfers/recipients/{id}", receiverId);
            default -> throw new IllegalArgumentException(name);
        };
    }
}
