package com.travelpay.payment.presentation.config;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.travelpay.payment.support.PostgresContainerSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 애플리케이션 구성으로 결제·결제 관리 API 문서를 생성해 확인합니다. 컨텍스트 기동에 DB(Testcontainers PostgreSQL)가 필요합니다. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "transfer.recovery.enabled=false",
        "payment.settlement-recovery.enabled=false"})
class PaymentApiDocsIntegrationTest extends PostgresContainerSupport {

    private static final String DOCS = "/v3/api-docs/payment";
    private static final String ADMIN_DOCS = "/v3/api-docs/payment-admin";
    private static final String PAY = "$.paths['/api/v1/payments'].post";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void documentsOnlyPaymentEndpoints() throws Exception {
        // 결제·환불·가맹점·정산. 송금(/api/v1/transfers)과 내부 API(/internal)는 들어가지 않음
        mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TravelPay 결제 API"))
                .andExpect(jsonPath("$.paths.keys()", containsInAnyOrder(
                        "/api/v1/payments",
                        "/api/v1/payments/{paymentId}",
                        "/api/v1/payments/{paymentId}/refunds",
                        "/api/v1/payments/{paymentId}/refund-request",
                        "/api/v1/payments/{paymentId}/refund-request/reject",
                        "/api/v1/payments/merchants",
                        "/api/v1/payments/merchants/{merchantId}/ledger-account",
                        "/api/v1/payments/merchants/{merchantId}/balance",
                        "/api/v1/payments/merchants/{merchantId}/payments",
                        "/api/v1/payments/merchants/{merchantId}/payments/{paymentId}",
                        "/api/v1/payments/merchants/{merchantId}/settlements",
                        "/api/v1/payments/merchants/{merchantId}/settlements/preview",
                        "/api/v1/payments/merchants/{merchantId}/settlements/{settlementId}",
                        "/api/v1/payments/settlements/me")));
    }

    @Test
    void documentsOnlyPaymentAdminEndpoints() throws Exception {
        mvc.perform(get(ADMIN_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TravelPay 결제 관리 API"))
                .andExpect(jsonPath("$.paths.keys()", containsInAnyOrder(
                        "/api/v1/admin/payments/refund-requests/overdue",
                        "/api/v1/admin/payments/manual-reviews",
                        "/api/v1/admin/payments/refunds/manual-reviews",
                        "/api/v1/admin/payments/resolutions",
                        "/api/v1/admin/payments/{paymentId}",
                        "/api/v1/admin/payments/refunds/{refundId}",
                        "/api/v1/admin/payments/{paymentId}/resolve",
                        "/api/v1/admin/payments/refunds/{refundId}/resolve",
                        "/api/v1/admin/payments/settlements/manual-reviews",
                        "/api/v1/admin/payments/settlements/resolutions",
                        "/api/v1/admin/payments/settlements/{settlementId}",
                        "/api/v1/admin/payments/settlements/{settlementId}/resolve")));
    }

    @Test
    void documentsHeadersWithoutExposingCurrentUserParameter() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PAY + ".parameters[*].name",
                        containsInAnyOrder("Idempotency-Key", "User-Id", "User-Role")))
                .andExpect(jsonPath(PAY + ".parameters[?(@.name == 'Idempotency-Key')].required", hasItem(true)))
                .andExpect(jsonPath("$.paths.*.*.parameters[?(@.name == 'user')]", empty()));
        mvc.perform(get(ADMIN_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths.*.*.parameters[?(@.name == 'user')]", empty()))
                .andExpect(jsonPath("$.paths['/api/v1/admin/payments/manual-reviews'].get.parameters[*].name",
                        hasItems("User-Id", "User-Role")));
    }

    @Test
    void documentsSummariesTagsAndSuccessResponsesOnly() throws Exception {
        // 오류 응답(4xx·5xx)은 문서에 적지 않음. 오류 코드는 docs/guide/payment.md에 정리
        mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(PAY + ".summary").value("결제 요청"))
                .andExpect(jsonPath(PAY + ".tags", hasItem("결제")))
                .andExpect(jsonPath(PAY + ".responses.keys()", contains("201")))
                .andExpect(jsonPath("$.paths['/api/v1/payments/{paymentId}/refunds'].post.responses.keys()",
                        contains("200")))
                .andExpect(jsonPath("$.paths['/api/v1/payments/merchants/{merchantId}/settlements'].post.responses.keys()",
                        contains("201")))
                // 성공 코드가 둘인 API는 둘 다 남김
                .andExpect(jsonPath("$.paths['/api/v1/payments/merchants'].post.responses.keys()",
                        containsInAnyOrder("201", "202")))
                .andExpect(onlySuccessResponses())
                // 가맹점 결제 조회는 가맹점 태그 아래에 묶음
                .andExpect(jsonPath("$.paths['/api/v1/payments/merchants/{merchantId}/payments'].get.tags", hasItem("가맹점")))
                .andExpect(jsonPath("$.paths['/api/v1/payments/merchants/{merchantId}/payments'].get.summary")
                        .value("가맹점 결제 목록 조회"));
        mvc.perform(get(ADMIN_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/admin/payments/settlements/{settlementId}/resolve'].post.summary")
                        .value("MANUAL_REVIEW 정산 처리"))
                .andExpect(jsonPath("$.paths['/api/v1/admin/payments/settlements/{settlementId}/resolve'].post.responses.keys()",
                        contains("200")))
                .andExpect(onlySuccessResponses());
    }

    // 문서의 모든 API 응답 코드가 2xx인지 확인 (오류 응답 4xx·5xx는 적지 않음)
    private static ResultMatcher onlySuccessResponses() {
        return result -> {
            List<Map<String, Object>> responses = JsonPath.read(result.getResponse().getContentAsString(),
                    "$.paths.*.*.responses");
            List<String> codes = responses.stream().flatMap(r -> r.keySet().stream()).toList();
            assertThat(codes).isNotEmpty().allMatch(code -> code.startsWith("2"));
        };
    }
}
