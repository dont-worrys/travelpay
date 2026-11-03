package com.travelpay.payment.transfer.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi;
import com.travelpay.payment.infra.client.user.UserFeignApi;
import com.travelpay.payment.infra.client.wallet.WalletFeignApi;
import com.travelpay.payment.transfer.controller.TransferController;
import jakarta.persistence.EntityManager;
import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 송금 API 통합 테스트 공통 구성입니다. 실제 PostgreSQL(Flyway 스키마)과 실제 어댑터·Reader·서비스·컨트롤러를 쓰고,
 * 다른 서비스로 나가는 Feign 인터페이스(원장·지갑·회원)만 Mock으로 바꿉니다. 실제 서비스 간 통신(E2E)은 아닙니다.
 * 각 테스트는 트랜잭션 안에서 실행 후 롤백되어 로컬 DB에 데이터를 남기지 않습니다.
 * 백그라운드 스케줄러(송금 복구·결제 정산)는 꺼서 로컬 DB의 기존 데이터를 건드리지 않습니다.
 * PAYMENT_DB_URL 등 DB 환경 변수가 있을 때만 실행합니다.
 */
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "transfer.recovery.enabled=false",
        "payment.settlement-recovery.enabled=false"
})
@Transactional
@EnabledIfEnvironmentVariable(named = "PAYMENT_DB_URL", matches = ".+")
public abstract class TransferApiIntegrationSupport {

    protected static final String BODY = """
            {"receiverId":"%s","amount":3000,"currency":"KRW","memo":"IT"}""";

    @Autowired
    protected WebApplicationContext context;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected EntityManager entityManager;

    @MockitoBean
    protected LedgerFeignApi ledgerFeignApi;

    @MockitoBean
    protected WalletFeignApi walletFeignApi;

    @MockitoBean
    protected UserFeignApi userFeignApi;

    protected MockMvc mvc;
    protected UUID senderId;
    protected UUID receiverId;
    protected UUID sourceWalletId;
    protected UUID targetWalletId;

    @BeforeEach
    void setUpTransferApi() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        senderId = UUID.randomUUID();
        receiverId = UUID.randomUUID();
        sourceWalletId = UUID.randomUUID();
        targetWalletId = UUID.randomUUID();
        givenWallet(senderId, sourceWalletId, "ACTIVE");
        givenWallet(receiverId, targetWalletId, "ACTIVE");
        when(userFeignApi.getActiveUser(receiverId))
                .thenReturn(ApiResponse.success(new UserFeignApi.UserResponse(receiverId, "홍길동")));
    }

    protected void givenWallet(UUID userId, UUID walletId, String status) {
        when(walletFeignApi.getWallet(userId)).thenReturn(ApiResponse.success(new WalletFeignApi.WalletResponse(walletId, status)));
    }

    protected static ApiResponse<LedgerFeignApi.TransactionResponse> posted(UUID transactionId) {
        return ApiResponse.success(new LedgerFeignApi.TransactionResponse(transactionId, "POSTED"));
    }

    protected MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder builder, UUID userId) {
        return builder.header(AuthHeaders.USER_ID, userId.toString()).header(AuthHeaders.USER_ROLE, "USER");
    }

    protected ResultActions requestTransfer(String idempotencyKey) throws Exception {
        return mvc.perform(asUser(post("/api/v1/transfers"), senderId)
                .header(TransferController.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.formatted(receiverId)));
    }

    protected static UUID transferIdOf(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(),
                "$.data.transferId"));
    }

    protected Map<String, Object> row(UUID transferId) {
        return jdbcTemplate.queryForMap("""
                select status, attempt_count, review_reason, failure_reason, sender_id, receiver_id,
                       source_wallet_id, target_wallet_id
                from transfer where id = ?""", transferId);
    }

    protected long countTransfersOf(UUID userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from transfer where sender_id = ? or receiver_id = ?", Long.class, userId, userId);
    }

    protected long countByStatus(String status) {
        return jdbcTemplate.queryForObject("select count(*) from transfer where status = ?", Long.class, status);
    }

    protected Instant updatedAt(UUID transferId) {
        return jdbcTemplate.queryForObject("select updated_at from transfer where id = ?", Timestamp.class, transferId)
                .toInstant();
    }

    /**
     * 대기 시간 경과·오래 처리 중인 상황을 만들기 위해 이 테스트가 만든 송금의 시각만 바꿉니다(롤백됨).
     * 테스트는 한 트랜잭션이라 SQL로 바꾼 시각이 영속성 컨텍스트에 남은 엔티티와 달라지므로 컨텍스트를 비웁니다.
     * (실제 복구는 주기마다 새 영속성 컨텍스트에서 실행됩니다.)
     */
    protected void setTimes(UUID transferId, Instant createdAt, Instant updatedAt) {
        jdbcTemplate.update("update transfer set created_at = ?, updated_at = ? where id = ?",
                createdAt.atOffset(ZoneOffset.UTC), updatedAt.atOffset(ZoneOffset.UTC), transferId);
        entityManager.clear();
    }

    protected static FeignException feignError(String methodKey, Request.HttpMethod method, String url,
            int status, String body) {
        Response.Builder builder = Response.builder()
                .status(status)
                .reason("error")
                .request(request(method, url))
                .headers(Map.of());
        if (body != null) {
            builder.body(body, UTF_8);
        }
        return FeignException.errorStatus(methodKey, builder.build());
    }

    protected static FeignException ledgerError(int status, String code) {
        return feignError("LedgerFeignApi#register(RegisterRequest)", Request.HttpMethod.POST,
                "http://ledger-service/internal/v1/ledger/transactions", status, errorBody(code));
    }

    protected static Request request(Request.HttpMethod method, String url) {
        return Request.create(method, url, Map.of(), null, UTF_8, null);
    }

    protected static String errorBody(String code) {
        return """
                {"success":false,"data":null,"error":{"code":"%s","message":"error","details":[]}}""".formatted(code);
    }
}
