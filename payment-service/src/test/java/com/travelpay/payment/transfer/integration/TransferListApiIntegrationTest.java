package com.travelpay.payment.transfer.integration;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.support.TransferApiIntegrationSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TC-25: 송금 목록 API(GET /api/v1/transfers)의 페이지네이션·방향 필터·정렬·사용자 범위를 실제 DB와 실제 MVC 구성으로 검증합니다.
 * 데이터는 이 테스트가 무작위 회원 ID로 만들고 롤백합니다.
 */
class TransferListApiIntegrationTest extends TransferApiIntegrationSupport {


    @Autowired
    private TransferRepository transferRepository;


    // PostgreSQL uuid 정렬(바이트 순)과 같도록 소문자 문자열로 비교합니다.
    private static final Comparator<Created> NEWEST_FIRST = Comparator
            .comparing(Created::createdAt).thenComparing(created -> created.id().toString()).reversed();

    private record Created(UUID id, Instant createdAt, boolean sent) {
    }

    /** TC-25-I1: 기본 크기 20, page 0부터, totalElements·totalPages, createdAt desc·id desc(같은 시각 포함), 다른 회원 송금 제외. */
    @Test
    void defaultPageSizeIsTwentyAndPagesFollowStableNewestFirstOrder() throws Exception {
        List<Created> related = createRelated(21);
        createUnrelated(3);
        List<String> expected = related.stream().sorted(NEWEST_FIRST).map(c -> c.id().toString()).toList();

        String first = list(get("/api/v1/transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(21))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(20))
                .andReturn().getResponse().getContentAsString();
        String second = list(get("/api/v1/transfers").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        List<String> actual = new ArrayList<>(JsonPath.<List<String>>read(first, "$.data.items[*].transferId"));
        actual.addAll(JsonPath.<List<String>>read(second, "$.data.items[*].transferId"));
        assertEquals(expected, actual);
    }

    /** TC-25-I2: 최대 크기 100을 넘으면 100으로 조회합니다. */
    @Test
    void sizeAboveMaximumIsLimitedToHundred() throws Exception {
        createRelated(101);

        list(get("/api/v1/transfers").param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100))
                .andExpect(jsonPath("$.data.items.length()").value(100))
                .andExpect(jsonPath("$.data.totalElements").value(101))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    /** TC-25-I3: direction=SENT·RECEIVED는 해당 방향만, 각 항목의 direction은 조회자 기준입니다. */
    @Test
    void directionFilterReturnsOnlyThatDirection() throws Exception {
        List<Created> related = createRelated(6);
        long sentCount = related.stream().filter(Created::sent).count();

        list(get("/api/v1/transfers").param("direction", "SENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(sentCount))
                .andExpect(jsonPath("$.data.items[?(@.direction != 'SENT')]").isEmpty())
                .andExpect(jsonPath("$.data.items[?(@.senderId != '" + senderId + "')]").isEmpty());
        list(get("/api/v1/transfers").param("direction", "RECEIVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(related.size() - sentCount))
                .andExpect(jsonPath("$.data.items[?(@.direction != 'RECEIVED')]").isEmpty())
                .andExpect(jsonPath("$.data.items[?(@.receiverId != '" + senderId + "')]").isEmpty());
    }

    /** TC-25-I4: 범위를 넘는 page는 빈 목록이고 전체 건수는 그대로입니다. */
    @Test
    void pageBeyondLastIsEmpty() throws Exception {
        createRelated(3);

        list(get("/api/v1/transfers").param("page", "5").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));
    }

    /** TC-25-I5: 송금 당사자가 아닌 회원에게는 다른 회원의 송금이 보이지 않습니다. */
    @Test
    void thirdPartySeesNothing() throws Exception {
        createRelated(3);

        mvc.perform(asUser(get("/api/v1/transfers"), UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    /**
     * TC-25-I7: 받은 내역은 완료(COMPLETED) 송금만 보입니다. 처리 중·실패·확인 대상 송금은 수취인에게 보이지 않고
     * (송금인 쪽 실패 사유·메모가 드러나지 않음) 송금인의 보낸 내역에는 모두 보입니다.
     */
    @Test
    void receiverSeesOnlyCompletedTransfersWhileSenderSeesEveryStatus() throws Exception {
        Instant base = Instant.parse("2026-10-28T03:00:00Z");
        UUID completed = save(receiverId, senderId, "tc25-done", base);
        saveIn(receiverId, senderId, "tc25-processing", base.plusSeconds(1), "PROCESSING", null, null);
        saveIn(receiverId, senderId, "tc25-failed", base.plusSeconds(2), "FAILED", "INSUFFICIENT_BALANCE", null);
        saveIn(receiverId, senderId, "tc25-review", base.plusSeconds(3), "MANUAL_REVIEW", null, "RETRY_EXHAUSTED");
        entityManager.clear();

        for (String direction : new String[] {"RECEIVED", null}) {
            var builder = get("/api/v1/transfers");
            if (direction != null) {
                builder.param("direction", direction);
            }
            list(builder)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.totalElements").value(1))
                    .andExpect(jsonPath("$.data.items[0].transferId").value(completed.toString()));
        }
        mvc.perform(asUser(get("/api/v1/transfers").param("direction", "SENT"), receiverId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4));
    }

    /** TC-25-I6: 잘못된 page·size·direction은 400 COMMON_INVALID_REQUEST입니다(1 미만 size는 400, 대소문자 다른 방향 값 포함). */
    @ParameterizedTest
    @CsvSource({"page, -1", "size, 0", "size, -5", "size, abc", "page, x", "direction, ALL", "direction, sent"})
    void rejectsInvalidParameters(String name, String value) throws Exception {
        list(get("/api/v1/transfers").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    private ResultActions list(MockHttpServletRequestBuilder builder) throws Exception {
        return mvc.perform(asUser(builder, senderId));
    }

    /** 송금인으로서 보낸 송금과 받은 송금을 번갈아 만들며, 3건마다 같은 생성 시각을 둬 보조 정렬(id)을 확인합니다. */
    private List<Created> createRelated(int count) {
        Instant base = Instant.parse("2026-10-28T01:00:00.123456Z");
        List<Created> created = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            boolean sent = i % 2 == 0;
            UUID other = UUID.randomUUID();
            Instant createdAt = base.plusSeconds(i / 3);
            UUID id = save(sent ? senderId : other, sent ? other : senderId, "tc25-" + i, createdAt);
            created.add(new Created(id, createdAt, sent));
        }
        entityManager.clear();
        return created;
    }

    private void createUnrelated(int count) {
        for (int i = 0; i < count; i++) {
            save(UUID.randomUUID(), UUID.randomUUID(), "tc25-x" + i, Instant.parse("2026-10-28T02:00:00Z"));
        }
        entityManager.clear();
    }

    /** 완료 송금을 만듭니다. 받은 내역에는 완료 송금만 보이므로 목록 테스트의 기본 데이터입니다. */
    private UUID save(UUID sender, UUID receiver, String key, Instant createdAt) {
        return saveIn(sender, receiver, key, createdAt, "COMPLETED", null, null);
    }

    /** 생성 경로는 PROCESSING뿐이므로 저장 뒤 SQL로 상태·사유(CHECK 조합)와 생성 시각을 맞춥니다. */
    private UUID saveIn(UUID sender, UUID receiver, String key, Instant createdAt,
                        String status, String failureReason, String reviewReason) {
        Transfer saved = transferRepository.saveAndFlush(Transfer.process(sender, receiver, UUID.randomUUID(),
                UUID.randomUUID(), 1_000L, "KRW", null, key));
        jdbcTemplate.update(
                "update transfer set created_at = ?, status = ?, failure_reason = ?, review_reason = ? where id = ?",
                createdAt.atOffset(ZoneOffset.UTC), status, failureReason, reviewReason, saved.getId());
        return saved.getId();
    }
}
