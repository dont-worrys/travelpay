package com.travelpay.exchange.presentation.order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUserArgumentResolver;
import com.travelpay.common.error.GlobalExceptionHandler;
import com.travelpay.exchange.application.order.AdminExchangeResolveResult;
import com.travelpay.exchange.application.order.AdminExchangeReviewDetailResult;
import com.travelpay.exchange.application.order.ExchangeAdminService;
import com.travelpay.exchange.application.order.ExchangeManualReviewView;
import com.travelpay.exchange.application.order.ExchangeResolutionCondition;
import com.travelpay.exchange.application.order.ExchangeResolutionView;
import com.travelpay.exchange.domain.ledger.LedgerLookupResult;
import com.travelpay.exchange.domain.order.ExchangeFailureReason;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ExchangeOrderStatusHistory;
import com.travelpay.exchange.domain.order.ManualReviewReason;
import com.travelpay.exchange.domain.order.ManualReviewResolution;
import com.travelpay.exchange.domain.order.ManualReviewResolutionReason;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminExchangeOrderControllerTest {

    private static final UUID MASTER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-11-01T05:00:00Z");

    private final ExchangeAdminService adminService = mock(ExchangeAdminService.class);
    private AuthenticatedUser user;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        user = new AuthenticatedUser(MASTER_ID, AuthRole.MASTER);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminExchangeOrderController(adminService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver(() -> Optional.ofNullable(user)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void nonMasterUserReceives403ForbiddenOnAllEndpoints() throws Exception {
        user = new AuthenticatedUser(USER_ID, AuthRole.USER);

        mockMvc.perform(get("/api/v1/admin/exchanges/manual-reviews"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/exchanges/" + ORDER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));

        mockMvc.perform(get("/api/v1/admin/exchanges/resolutions"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));

        mockMvc.perform(post("/api/v1/admin/exchanges/" + ORDER_ID + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":\"test\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("COMMON_FORBIDDEN"));

        verifyNoInteractions(adminService);
    }

    @Test
    void listManualReviewsReturns200WithPageResponse() throws Exception {
        ExchangeOrder order = order();
        ExchangeManualReviewView view = new ExchangeManualReviewView(order, ManualReviewReason.LEDGER_CONFLICT);
        when(adminService.getManualReviews(eq(MASTER_ID), eq(ManualReviewReason.LEDGER_CONFLICT), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(view)));

        mockMvc.perform(get("/api/v1/admin/exchanges/manual-reviews")
                        .param("reviewReason", "LEDGER_CONFLICT")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.items[0].orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].status").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data.items[0].reviewReason").value("LEDGER_CONFLICT"))
                .andExpect(jsonPath("$.data.items[0].fromCurrency").value("KRW"))
                .andExpect(jsonPath("$.data.items[0].toCurrency").value("JPY"));
    }

    /** /resolutions는 /{orderId}로 해석되지 않고(400 주문 ID 형식 오류가 아님) 처리 이력 목록으로 갑니다. */
    @Test
    void listResolutionsReturns200WithFiltersAndAttachedReasons() throws Exception {
        UUID ledgerTxId = UUID.randomUUID();
        ExchangeOrderStatusHistory history = ExchangeOrderStatusHistory.byMaster(ORDER_ID,
                ExchangeOrderStatus.MANUAL_REVIEW, ExchangeOrderStatus.FAILED, "LEDGER_REJECTED",
                MASTER_ID, ledgerTxId, "원장 거절 확인");
        ReflectionTestUtils.setField(history, "createdAt", NOW);
        ExchangeResolutionCondition condition = new ExchangeResolutionCondition(
                MASTER_ID, ORDER_ID, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-02T00:00:00Z"));
        when(adminService.getResolutions(MASTER_ID, condition, 1, 10)).thenReturn(new PageImpl<>(List.of(
                new ExchangeResolutionView(history, ManualReviewReason.LEDGER_UNKNOWN_REJECTION,
                        ExchangeFailureReason.INSUFFICIENT_BALANCE))));

        mockMvc.perform(get("/api/v1/admin/exchanges/resolutions")
                        .param("actorId", MASTER_ID.toString())
                        .param("orderId", ORDER_ID.toString())
                        .param("from", "2026-11-01T00:00:00Z")
                        .param("to", "2026-11-02T00:00:00Z")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].actorId").value(MASTER_ID.toString()))
                .andExpect(jsonPath("$.data.items[0].fromStatus").value("MANUAL_REVIEW"))
                .andExpect(jsonPath("$.data.items[0].toStatus").value("FAILED"))
                .andExpect(jsonPath("$.data.items[0].resolutionReason").value("LEDGER_REJECTED"))
                .andExpect(jsonPath("$.data.items[0].reviewReason").value("LEDGER_UNKNOWN_REJECTION"))
                .andExpect(jsonPath("$.data.items[0].failureReason").value("INSUFFICIENT_BALANCE"))
                .andExpect(jsonPath("$.data.items[0].ledgerTransactionId").value(ledgerTxId.toString()))
                .andExpect(jsonPath("$.data.items[0].memo").value("원장 거절 확인"))
                .andExpect(jsonPath("$.data.items[0].resolvedAt").value("2026-11-01T05:00:00Z"));
    }

    @Test
    void listResolutionsWithoutFiltersPassesNullConditions() throws Exception {
        when(adminService.getResolutions(MASTER_ID, new ExchangeResolutionCondition(null, null, null, null), 0, 20))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/exchanges/resolutions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    /** 형식이 틀린 조건은 서비스에 닿기 전에 400입니다. */
    @Test
    void listResolutionsRejectsMalformedFilters() throws Exception {
        mockMvc.perform(get("/api/v1/admin/exchanges/resolutions").param("actorId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/admin/exchanges/resolutions").param("from", "2026-11-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));

        verifyNoInteractions(adminService);
    }

    @Test
    void getManualReviewReturns200WithDetailAndPreview() throws Exception {
        ExchangeOrder order = order();
        UUID txId = UUID.randomUUID();
        LedgerLookupResult.Posted posted = new LedgerLookupResult.Posted(txId, order.expectedLedgerEntries());
        ManualReviewResolution.Decision decision = ManualReviewResolution.decide(
                order, ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG, posted);

        AdminExchangeReviewDetailResult detailResult = AdminExchangeReviewDetailResult.of(
                order, ManualReviewReason.LEDGER_UNCONFIRMED_TOO_LONG, posted, decision);

        when(adminService.getManualReview(MASTER_ID, ORDER_ID)).thenReturn(detailResult);

        mockMvc.perform(get("/api/v1/admin/exchanges/" + ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.data.reviewReason").value("LEDGER_UNCONFIRMED_TOO_LONG"))
                .andExpect(jsonPath("$.data.ledgerLookupFailed").value(false))
                .andExpect(jsonPath("$.data.ledger.status").value("POSTED"))
                .andExpect(jsonPath("$.data.ledger.transactionId").value(txId.toString()))
                .andExpect(jsonPath("$.data.expected.resolvable").value(true))
                .andExpect(jsonPath("$.data.expected.toStatus").value("COMPLETED"));
    }

    @Test
    void resolveReturns200WithResolvedResponse() throws Exception {
        UUID ledgerTxId = UUID.randomUUID();
        AdminExchangeResolveResult resolveResult = new AdminExchangeResolveResult(
                ORDER_ID,
                ExchangeOrderStatus.COMPLETED,
                null,
                ManualReviewResolutionReason.LEDGER_POSTED,
                ledgerTxId,
                NOW
        );

        when(adminService.resolveManualReview(eq(MASTER_ID), eq(ORDER_ID), eq("확인 완료")))
                .thenReturn(resolveResult);

        mockMvc.perform(post("/api/v1/admin/exchanges/" + ORDER_ID + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":\"확인 완료\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.orderId").value(ORDER_ID.toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.reason").value("LEDGER_POSTED"))
                .andExpect(jsonPath("$.data.ledgerTransactionId").value(ledgerTxId.toString()));
    }

    @Test
    void resolveRejectsMemoExceeding255Chars() throws Exception {
        String longMemo = "a".repeat(256);

        mockMvc.perform(post("/api/v1/admin/exchanges/" + ORDER_ID + "/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memo\":\"" + longMemo + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));

        verifyNoInteractions(adminService);
    }

    private static ExchangeOrder order() {
        Instant issuedAt = Instant.parse("2026-10-29T03:00:00Z");
        ExchangeQuote quote = OrderFixtures.wonToYenQuote(USER_ID,
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), issuedAt);
        ReflectionTestUtils.setField(quote, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(quote, "createdAt", issuedAt);
        ExchangeOrder order = OrderFixtures.placed(quote, WALLET_ID, "idemp-key-1");
        ReflectionTestUtils.setField(order, "id", ORDER_ID);
        ReflectionTestUtils.setField(order, "status", ExchangeOrderStatus.MANUAL_REVIEW);
        return order;
    }
}
