package com.travelpay.wallet.presentation.charge;

import com.travelpay.wallet.application.charge.ChargeTransactionSteps;
import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import com.travelpay.wallet.domain.charge.BankAccount;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 실제 Postgres(Flyway 적용)에서 충전 내역 조회 API(회원·관리자)를 검증한다.
 * 관리자 API는 Gateway 라우트가 아직 없어 wallet-service를 직접 호출한다. Docker가 없으면 건너뛴다.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "travelpay.wallet.outbox.publisher.enabled=false",
        "travelpay.wallet.charge.recovery.enabled=false",
        "travelpay.wallet.charge.reconciliation.enabled=false",
        "travelpay.wallet.charge.saga.consumer.enabled=false",
        "spring.kafka.admin.auto-create=false"
})
class ChargeQueryIntegrationTest {

    private static final String SCHEMA = "wallet_service";
    private static final String ACCOUNT_NUMBER = "1234567890";
    private static final String MASKED = "******7890";
    private static final String REAL_NAME = "홍길동";
    private static final Instant T1 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant T2 = Instant.parse("2026-11-02T00:00:00Z");
    private static final Instant T3 = Instant.parse("2026-11-03T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6-bookworm")
            .withUrlParam("currentSchema", SCHEMA);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("travelpay.wallet.account-number-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ChargeTransactionSteps steps;

    @Autowired
    private AccountNumberCipher cipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private UUID me;
    private UUID other;
    private UUID myWallet;
    private UUID otherWallet;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".charge_status_history, " + SCHEMA + ".outbox_events, "
                + SCHEMA + ".charge_requests, " + SCHEMA + ".wallet_currencies, " + SCHEMA + ".wallets");
        me = UUID.randomUUID();
        other = UUID.randomUUID();
        myWallet = insertWallet(me);
        otherWallet = insertWallet(other);
    }

    // ---- 회원 목록 ----

    @Test
    void 본인_충전만_최신순으로_나온다() throws Exception {
        UUID c1 = charge(me, myWallet, T1);
        UUID c2 = charge(me, myWallet, T2);
        UUID c3 = charge(me, myWallet, T3);
        charge(other, otherWallet, T2);

        asUser(get("/api/v1/charges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(c3.toString(), c2.toString(), c1.toString())))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void 생성_시각이_같으면_id_내림차순이다() throws Exception {
        charge(me, myWallet, T1);
        charge(me, myWallet, T1);
        charge(me, myWallet, T1);
        List<String> expected = jdbcTemplate.queryForList(
                "SELECT id::text FROM charge_requests WHERE user_id = ? ORDER BY id DESC", String.class, me);

        asUser(get("/api/v1/charges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(expected.toArray())));
    }

    @Test
    void 상태_필터() throws Exception {
        charge(me, myWallet, T1);
        UUID failed = charge(me, myWallet, T2);
        setStatus(failed, "FAILED", "BANK_REJECTED", 0);

        asUser(get("/api/v1/charges").param("status", "FAILED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(failed.toString())))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 기간_필터는_from_포함_to_미포함() throws Exception {
        charge(me, myWallet, T1);
        UUID c2 = charge(me, myWallet, T2);
        charge(me, myWallet, T3);

        asUser(get("/api/v1/charges").param("from", T2.toString()).param("to", T3.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(c2.toString())))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 잘못된_요청은_400() throws Exception {
        asUser(get("/api/v1/charges").param("from", T3.toString()).param("to", T1.toString()))
                .andExpect(status().isBadRequest());
        asUser(get("/api/v1/charges").param("status", "NOPE")).andExpect(status().isBadRequest());
        asUser(get("/api/v1/charges").param("from", "2026-11-01")).andExpect(status().isBadRequest());
        asUser(get("/api/v1/charges").param("page", "-1")).andExpect(status().isBadRequest());
        asUser(get("/api/v1/charges").param("size", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void 페이지_경계() throws Exception {
        charge(me, myWallet, T1);

        asUser(get("/api/v1/charges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
        asUser(get("/api/v1/charges").param("size", "100"))
                .andExpect(jsonPath("$.data.size").value(100));
        asUser(get("/api/v1/charges").param("size", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    /** 첫 페이지가 가득 차야 Spring Data가 countQuery를 실행한다. 필터 없음·필터 있음 모두 count가 맞는지 확인 */
    @Test
    void 여러_페이지면_count_쿼리로_전체_건수를_센다() throws Exception {
        UUID c1 = charge(me, myWallet, T1);
        UUID c2 = charge(me, myWallet, T2);
        charge(me, myWallet, T3);
        charge(other, otherWallet, T2);
        setStatus(c1, "FAILED", "BANK_REJECTED", 0);
        setStatus(c2, "FAILED", "BANK_REJECTED", 0);

        asUser(get("/api/v1/charges").param("size", "1").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(c2.toString())))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(3));
        asUser(get("/api/v1/charges").param("size", "1").param("status", "FAILED").param("from", T1.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        asMaster(get("/api/v1/admin/charges").param("size", "1"))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.totalPages").value(4));
        asMaster(get("/api/v1/admin/charges").param("size", "1").param("userId", me.toString())
                .param("status", "FAILED").param("to", T3.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(2));
    }

    @Test
    void MASTER는_회원_목록에_403() throws Exception {
        mockMvc.perform(get("/api/v1/charges").header("User-Id", me.toString()).header("User-Role", "MASTER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 응답에_계좌번호_원문과_실명이_없다() throws Exception {
        UUID chargeId = charge(me, myWallet, T1);

        String userList = body(asUser(get("/api/v1/charges")).andExpect(status().isOk()));
        String adminList = body(asMaster(get("/api/v1/admin/charges")).andExpect(status().isOk()));
        String single = body(asUser(get("/api/v1/charges/{id}", chargeId)).andExpect(status().isOk()));

        for (String json : List.of(userList, adminList, single)) {
            assertFalse(json.contains(ACCOUNT_NUMBER), json);
            assertFalse(json.contains(REAL_NAME), json);
            assertFalse(json.contains("holderName"), json);
            assertFalse(json.contains("accountNumberEnc"), json);
            assertFalse(json.contains("lastError"), json);
        }
        asUser(get("/api/v1/charges")).andExpect(jsonPath("$.data.items[0].accountNumberMask").value(MASKED));
    }

    // ---- 기존 단건 조회 보호 (toResult 추출 후 같은 응답) ----

    @Test
    void 단건_조회_응답_필드가_그대로다() throws Exception {
        UUID chargeId = charge(me, myWallet, T1);
        setStatus(chargeId, "FAILED", "BANK_REJECTED", 2);
        Instant updatedAt = Instant.parse("2026-11-01T00:05:00Z");
        jdbcTemplate.update("UPDATE charge_requests SET updated_at = ? WHERE id = ?",
                Timestamp.from(updatedAt), chargeId);

        asUser(get("/api/v1/charges/{id}", chargeId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10))
                .andExpect(jsonPath("$.data.chargeId").value(chargeId.toString()))
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.amount").value(10_000))
                .andExpect(jsonPath("$.data.currency").value("KRW"))
                .andExpect(jsonPath("$.data.bankCode").value("004"))
                .andExpect(jsonPath("$.data.accountNumberMask").value(MASKED))
                .andExpect(jsonPath("$.data.failureCode").value("BANK_REJECTED"))
                .andExpect(jsonPath("$.data.failureMessage").value("은행에서 출금을 거절했습니다."))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-01T00:00:00Z"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-11-01T00:05:00Z"));

        // 목록 항목도 단건과 같은 값
        asUser(get("/api/v1/charges"))
                .andExpect(jsonPath("$.data.items[0].length()").value(10))
                .andExpect(jsonPath("$.data.items[0].accountNumberMask").value(MASKED))
                .andExpect(jsonPath("$.data.items[0].failureMessage").value("은행에서 출금을 거절했습니다."));
    }

    @Test
    void 타인_충전_단건_조회는_404() throws Exception {
        UUID othersCharge = charge(other, otherWallet, T1);
        asUser(get("/api/v1/charges/{id}", othersCharge)).andExpect(status().isNotFound());
    }

    // ---- 관리자 목록 ----

    @Test
    void 관리자_필터_없이_전체_조회() throws Exception {
        UUID c1 = charge(me, myWallet, T1);
        UUID c2 = charge(other, otherWallet, T2);
        setStatus(c2, "MANUAL_REVIEW", "RETRY_EXHAUSTED", 5);

        asMaster(get("/api/v1/admin/charges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(c2.toString(), c1.toString())))
                .andExpect(jsonPath("$.data.items[0].userId").value(other.toString()))
                .andExpect(jsonPath("$.data.items[0].attemptCount").value(5))
                .andExpect(jsonPath("$.data.items[0].accountNumberMask").value(MASKED))
                .andExpect(jsonPath("$.data.items[1].userId").value(me.toString()))
                .andExpect(jsonPath("$.data.items[1].attemptCount").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    void 관리자_userId와_MANUAL_REVIEW_필터() throws Exception {
        UUID mine = charge(me, myWallet, T1);
        UUID myReview = charge(me, myWallet, T2);
        UUID othersReview = charge(other, otherWallet, T3);
        setStatus(myReview, "MANUAL_REVIEW", "RETRY_EXHAUSTED", 3);
        setStatus(othersReview, "MANUAL_REVIEW", "RETRY_EXHAUSTED", 4);

        asMaster(get("/api/v1/admin/charges").param("userId", me.toString()))
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(myReview.toString(), mine.toString())));
        asMaster(get("/api/v1/admin/charges").param("status", "MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data.items[*].chargeId",
                        contains(othersReview.toString(), myReview.toString())))
                .andExpect(jsonPath("$.data.items[*].status", everyItem(is("MANUAL_REVIEW"))));
        asMaster(get("/api/v1/admin/charges").param("userId", me.toString()).param("status", "MANUAL_REVIEW")
                .param("from", T1.toString()).param("to", T3.toString()))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].chargeId").value(myReview.toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void 관리자_API에_USER는_403() throws Exception {
        asUser(get("/api/v1/admin/charges")).andExpect(status().isForbidden());
    }

    @Test
    void 관리자_잘못된_요청은_400() throws Exception {
        asMaster(get("/api/v1/admin/charges").param("userId", "not-a-uuid")).andExpect(status().isBadRequest());
        asMaster(get("/api/v1/admin/charges").param("status", "NOPE")).andExpect(status().isBadRequest());
        asMaster(get("/api/v1/admin/charges").param("size", "0")).andExpect(status().isBadRequest());
        asMaster(get("/api/v1/admin/charges").param("from", T3.toString()).param("to", T1.toString()))
                .andExpect(status().isBadRequest());
        asMaster(get("/api/v1/admin/charges").param("size", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    // ---- offset 범위·조회 경계 마이크로초 올림 (회원·관리자) ----

    @Test
    void offset이_int_범위를_넘는_page는_400() throws Exception {
        assertOffsetBounds(false);
    }

    @Test
    void 관리자_offset이_int_범위를_넘는_page는_400() throws Exception {
        assertOffsetBounds(true);
    }

    @Test
    void from이_저장_시각보다_100ns_뒤면_제외() throws Exception {
        assertFromSubMicrosExcludes(false);
    }

    @Test
    void 관리자_from이_저장_시각보다_100ns_뒤면_제외() throws Exception {
        assertFromSubMicrosExcludes(true);
    }

    @Test
    void to가_저장_시각보다_100ns_뒤면_포함() throws Exception {
        assertToSubMicrosIncludes(false);
    }

    @Test
    void 관리자_to가_저장_시각보다_100ns_뒤면_포함() throws Exception {
        assertToSubMicrosIncludes(true);
    }

    @Test
    void 마이크로초_경계는_기존_결과_그대로() throws Exception {
        assertMicrosBoundaryUnchanged(false);
    }

    @Test
    void 관리자_마이크로초_경계는_기존_결과_그대로() throws Exception {
        assertMicrosBoundaryUnchanged(true);
    }

    private void assertOffsetBounds(boolean admin) throws Exception {
        charge(me, myWallet, T1);

        list(admin, get(listPath(admin)).param("page", "21474837").param("size", "100")) // offset 2,147,483,700
                .andExpect(status().isBadRequest());
        list(admin, get(listPath(admin)).param("page", "21474837").param("size", "101")) // 101 → 100 보정 뒤 계산
                .andExpect(status().isBadRequest());
        list(admin, get(listPath(admin)).param("page", "21474836").param("size", "100")) // offset 2,147,483,600
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    private void assertFromSubMicrosExcludes(boolean admin) throws Exception {
        charge(me, myWallet, T2);

        list(admin, get(listPath(admin)).param("from", T2.plusNanos(100).toString()).param("to", T3.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    private void assertToSubMicrosIncludes(boolean admin) throws Exception {
        UUID c2 = charge(me, myWallet, T2);

        list(admin, get(listPath(admin)).param("from", T1.toString()).param("to", T2.plusNanos(100).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(c2.toString())));
    }

    private void assertMicrosBoundaryUnchanged(boolean admin) throws Exception {
        UUID c2 = charge(me, myWallet, T2);
        Instant nextMicro = T2.plusNanos(1_000);

        list(admin, get(listPath(admin)).param("from", T2.toString()).param("to", nextMicro.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[*].chargeId", contains(c2.toString())));
        list(admin, get(listPath(admin)).param("from", nextMicro.toString()).param("to", T3.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(0)));
    }

    // ---- helpers ----

    private static String listPath(boolean admin) {
        return admin ? "/api/v1/admin/charges" : "/api/v1/charges";
    }

    private ResultActions list(boolean admin, MockHttpServletRequestBuilder request) throws Exception {
        return admin ? asMaster(request) : asUser(request);
    }

    private ResultActions asUser(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("User-Id", me.toString()).header("User-Role", "USER"));
    }

    private ResultActions asMaster(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("User-Id", UUID.randomUUID().toString()).header("User-Role", "MASTER"));
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private UUID insertWallet(UUID userId) {
        UUID walletId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (id, user_id, status) VALUES (?, ?, 'ACTIVE')", walletId, userId);
        return walletId;
    }

    private UUID charge(UUID userId, UUID walletId, Instant createdAt) {
        ChargeRequest saved = steps.accept(ChargeRequest.create(userId, walletId, 10_000, Currency.KRW,
                BankAccount.encrypt("004", ACCOUNT_NUMBER, cipher), REAL_NAME, UUID.randomUUID()));
        jdbcTemplate.update("UPDATE charge_requests SET created_at = ?, updated_at = ? WHERE id = ?",
                Timestamp.from(createdAt), Timestamp.from(createdAt), saved.getId());
        return saved.getId();
    }

    private void setStatus(UUID chargeId, String status, String failureCode, int attemptCount) {
        jdbcTemplate.update("UPDATE charge_requests SET status = ?, failure_code = ?, attempt_count = ? WHERE id = ?",
                status, failureCode, attemptCount, chargeId);
    }
}
