package com.travelpay.payment.transfer.integration;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 송금 조회 API를 실제 MVC·서비스·PostgreSQL로 확인합니다. 송금은 SQL로 직접 넣어 상태·통화·시각을 정합니다(롤백됨).
 * 공유 컨테이너에 다른 테스트 데이터가 있을 수 있어, 목록 검증은 테스트마다 새 회원 ID나 먼 미래 시각 범위로 좁힙니다.
 */
class AdminTransferApiIntegrationTest extends TransferApiIntegrationSupport {

    private static final String LIST = "/api/v1/admin/transfers";
    private static final Instant BASE = Instant.parse("2099-01-01T00:00:00Z");

    private final UUID masterId = UUID.randomUUID();

    @Test
    void rejectsNonMasterWithForbidden() throws Exception {
        UUID transferId = insert(senderId, receiverId, "KRW", "COMPLETED", BASE);

        mvc.perform(asUser(get(LIST), senderId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));
        mvc.perform(asUser(get(LIST + "/" + transferId), senderId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));
    }

    @Test
    void rejectsRequestWithoutMemberHeaders() throws Exception {
        mvc.perform(get(LIST)).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsDetailWithOperationalFieldsForNonParticipantMaster() throws Exception {
        UUID transferId = insertManualReview(senderId, receiverId, BASE);

        mvc.perform(asMaster(get(LIST + "/" + transferId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.data.senderId").value(senderId.toString()))
                .andExpect(jsonPath("$.data.receiverId").value(receiverId.toString()))
                .andExpect(jsonPath("$.data.sourceWalletId").value(sourceWalletId.toString()))
                .andExpect(jsonPath("$.data.targetWalletId").value(targetWalletId.toString()))
                .andExpect(jsonPath("$.data.status").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data.reviewReason").value("RETRY_EXHAUSTED"))
                .andExpect(jsonPath("$.data.failureReason").doesNotExist())
                .andExpect(jsonPath("$.data.attemptCount").value(5))
                .andExpect(jsonPath("$.data.createdAt").value("2099-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.updatedAt").exists())
                .andExpect(jsonPath("$.data.direction").doesNotExist())
                .andExpect(jsonPath("$.data.idempotencyKey").doesNotExist());
    }

    @Test
    void returnsFailureReasonForFailedTransfer() throws Exception {
        UUID transferId = insertFailed(senderId, receiverId, BASE);

        mvc.perform(asMaster(get(LIST + "/" + transferId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("INSUFFICIENT_BALANCE"));
    }

    @Test
    void returnsNotFoundForMissingTransfer() throws Exception {
        mvc.perform(asMaster(get(LIST + "/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void filtersByUserAsSenderOrReceiver() throws Exception {
        UUID other = UUID.randomUUID();
        UUID sent = insert(senderId, receiverId, "KRW", "COMPLETED", BASE);
        UUID received = insert(other, senderId, "KRW", "COMPLETED", BASE.plusSeconds(1));
        insert(other, receiverId, "KRW", "COMPLETED", BASE.plusSeconds(2));

        mvc.perform(asMaster(get(LIST).param("userId", senderId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.items[*].transferId", contains(received.toString(), sent.toString())));
    }

    @Test
    void filtersByStatusAndCurrency() throws Exception {
        UUID match = insert(senderId, receiverId, "JPY", "COMPLETED", BASE);
        insert(senderId, receiverId, "KRW", "COMPLETED", BASE.plusSeconds(1));
        insertFailed(senderId, receiverId, BASE.plusSeconds(2));

        mvc.perform(asMaster(get(LIST).param("userId", senderId.toString())
                        .param("status", "COMPLETED").param("currency", "JPY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.items[0].transferId").value(match.toString()));
    }

    @Test
    void appliesPeriodWithInclusiveFromAndExclusiveTo() throws Exception {
        insert(senderId, receiverId, "KRW", "COMPLETED", BASE.minusNanos(1_000));
        UUID atFrom = insert(senderId, receiverId, "KRW", "COMPLETED", BASE);
        UUID inside = insert(senderId, receiverId, "KRW", "COMPLETED", BASE.plusSeconds(30));
        insert(senderId, receiverId, "KRW", "COMPLETED", BASE.plusSeconds(60));

        mvc.perform(asMaster(get(LIST)
                        .param("from", "2099-01-01T00:00:00Z")
                        .param("to", "2099-01-01T00:01:00Z")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].transferId",
                        containsInAnyOrder(atFrom.toString(), inside.toString())));
    }

    @Test
    void acceptsPeriodWithOffset() throws Exception {
        UUID transferId = insert(senderId, receiverId, "KRW", "COMPLETED", BASE);

        mvc.perform(asMaster(get(LIST).param("userId", senderId.toString())
                        .param("from", "2099-01-01T09:00:00+09:00")
                        .param("to", "2099-01-01T09:00:01+09:00")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].transferId", contains(transferId.toString())));
    }

    @Test
    void sortsLatestByDefaultAndOldestOnRequestWithIdTieBreak() throws Exception {
        UUID first = insert(senderId, receiverId, "KRW", "COMPLETED", BASE);
        UUID tieA = insert(senderId, receiverId, "KRW", "COMPLETED", BASE.plusSeconds(1));
        UUID tieB = insert(senderId, receiverId, "KRW", "COMPLETED", BASE.plusSeconds(1));
        String low = min(tieA, tieB).toString();
        String high = max(tieA, tieB).toString();

        mvc.perform(asMaster(get(LIST).param("userId", senderId.toString())))
                .andExpect(jsonPath("$.data.items[*].transferId", contains(high, low, first.toString())));
        mvc.perform(asMaster(get(LIST).param("userId", senderId.toString()).param("orderType", "OLDEST")))
                .andExpect(jsonPath("$.data.items[*].transferId", contains(first.toString(), low, high)));
    }

    /** 페이지를 나눠 조회하고, 최대 크기(100)를 넘는 size는 100으로 줄여 조회하는지 확인합니다. */
    @Test
    void pagesResultsAndCapsPageSize() throws Exception {
        for (int i = 0; i < 3; i++) {
            insert(senderId, receiverId, "KRW", "COMPLETED", BASE.plusSeconds(i));
        }

        mvc.perform(asMaster(get(LIST).param("userId", senderId.toString()).param("page", "1").param("size", "2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(asMaster(get(LIST).param("userId", senderId.toString()).param("size", "500")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void rejectsInvalidConditions() throws Exception {
        expectBadRequest(get(LIST).param("from", "2099-01-02T00:00:00Z").param("to", "2099-01-01T00:00:00Z"),
                "COMMON_INVALID_REQUEST");
        expectBadRequest(get(LIST).param("from", "2099-01-01T00:00:00Z").param("to", "2099-01-01T00:00:00Z"),
                "COMMON_INVALID_REQUEST");
        expectBadRequest(get(LIST).param("from", "2099-01-01"), "COMMON_INVALID_REQUEST");
        expectBadRequest(get(LIST).param("status", "DONE"), "COMMON_INVALID_REQUEST");
        expectBadRequest(get(LIST).param("orderType", "AMOUNT"), "COMMON_INVALID_REQUEST");
        expectBadRequest(get(LIST).param("userId", "not-uuid"), "COMMON_INVALID_REQUEST");
        expectBadRequest(get(LIST).param("page", "-1"), "COMMON_INVALID_REQUEST");
        expectBadRequest(get(LIST).param("size", "0"), "COMMON_INVALID_REQUEST");
        expectBadRequest(get(LIST).param("currency", "USD"), "TRANSFER_UNSUPPORTED_CURRENCY");
    }

    @Test
    void documentsAdminEndpointsInSeparateGroup() throws Exception {
        mvc.perform(get("/v3/api-docs/transfer-admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TravelPay 송금 관리 API"))
                .andExpect(jsonPath("$.paths.keys()", containsInAnyOrder(
                        "/api/v1/admin/transfers", "/api/v1/admin/transfers/{transferId}",
                        "/api/v1/admin/transfers/{transferId}/resolve",
                        "/api/v1/admin/transfers/{transferId}/history")))
                .andExpect(jsonPath("$.paths['/api/v1/admin/transfers/{transferId}/resolve'].post.responses.keys()",
                        hasItems("200", "400", "403", "404", "409", "503")))
                .andExpect(jsonPath("$.paths['/api/v1/admin/transfers'].get.parameters[*].name", containsInAnyOrder(
                        "from", "to", "currency", "status", "userId", "orderType", "page", "size",
                        "User-Id", "User-Role")))
                .andExpect(jsonPath("$.paths['/api/v1/admin/transfers'].get.responses.keys()",
                        hasItems("200", "400", "403")))
                .andExpect(jsonPath("$.paths['/api/v1/admin/transfers/{transferId}'].get.responses.keys()",
                        hasItems("200", "403", "404")));
    }

    private void expectBadRequest(MockHttpServletRequestBuilder builder, String code) throws Exception {
        ResultActions result = mvc.perform(asMaster(builder));
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(code));
    }

    private MockHttpServletRequestBuilder asMaster(MockHttpServletRequestBuilder builder) {
        return builder.header(AuthHeaders.USER_ID, masterId.toString()).header(AuthHeaders.USER_ROLE, "MASTER");
    }

    private UUID insert(UUID sender, UUID receiver, String currency, String status, Instant createdAt) {
        return insertRow(sender, receiver, currency, status, null, 0, null, createdAt);
    }

    private UUID insertFailed(UUID sender, UUID receiver, Instant createdAt) {
        return insertRow(sender, receiver, "KRW", "FAILED", "INSUFFICIENT_BALANCE", 0, null, createdAt);
    }

    private UUID insertManualReview(UUID sender, UUID receiver, Instant createdAt) {
        return insertRow(sender, receiver, "KRW", "MANUAL_REVIEW", null, 5, "RETRY_EXHAUSTED", createdAt);
    }

    private UUID insertRow(UUID sender, UUID receiver, String currency, String status, String failureReason,
            int attemptCount, String reviewReason, Instant createdAt) {
        return insertTransferRow(sender, receiver, currency, status, failureReason, attemptCount, reviewReason,
                createdAt);
    }

    /**
     * DB 정렬 기준으로 앞선 UUID를 반환합니다.
     * PostgreSQL uuid 정렬은 바이트 순서이며, Java UUID.compareTo(부호 있는 비교)와 다를 수 있어 문자열로 비교합니다.
     */
    private static UUID min(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? a : b;
    }

    /** DB 정렬 기준으로 뒤에 오는 UUID를 반환합니다({@link #min}과 같은 문자열 비교). */
    private static UUID max(UUID a, UUID b) {
        return a.toString().compareTo(b.toString()) <= 0 ? b : a;
    }
}
