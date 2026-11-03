package com.travelpay.payment.presentation.transfer;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.travelpay.payment.application.transfer.dto.TransferResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** 실제 애플리케이션 구성으로 송금 API 문서를 생성해 확인합니다. 컨텍스트 기동에 DB가 필요합니다. */
@SpringBootTest(properties = {"eureka.client.enabled=false", "transfer.recovery.enabled=false"})
@EnabledIfEnvironmentVariable(named = "PAYMENT_DB_URL", matches = ".+")
class TransferApiDocsIntegrationTest {

    private static final String DOCS = "/v3/api-docs/transfer";
    private static final String CREATE = "$.paths['/api/v1/transfers'].post";
    private static final String LIST = "$.paths['/api/v1/transfers'].get";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void documentsOnlyTransferEndpoints() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("TravelPay 송금 API"))
                .andExpect(jsonPath("$.paths.keys()", containsInAnyOrder(
                        "/api/v1/transfers",
                        "/api/v1/transfers/{transferId}",
                        "/api/v1/transfers/{transferId}/cancel",
                        "/api/v1/transfers/recipients/{receiverId}")));
    }

    @Test
    void documentsHeadersWithoutExposingCurrentUserParameter() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CREATE + ".parameters[*].name",
                        containsInAnyOrder("Idempotency-Key", "User-Id", "User-Role")))
                .andExpect(jsonPath(CREATE + ".parameters[?(@.name == 'Idempotency-Key')].required", hasItem(true)))
                .andExpect(jsonPath(LIST + ".parameters[*].name",
                        containsInAnyOrder("direction", "page", "size", "User-Id", "User-Role")))
                .andExpect(jsonPath("$.paths.*.*.parameters[?(@.name == 'user')]", empty()));
    }

    @Test
    void documentsTransferResponseCodes() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(CREATE + ".responses.keys()",
                        hasItems("201", "202", "400", "409", "422", "503")))
                .andExpect(jsonPath(CREATE + ".responses.keys()", not(hasItem("200"))))
                .andExpect(jsonPath("$.paths['/api/v1/transfers/{transferId}/cancel'].post.responses.keys()",
                        hasItems("200", "404", "409")));
    }

    @Test
    void documentsTransferStatusValues() throws Exception {
        mvc.perform(get(DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.TransferResponse.properties.status.enum", containsInAnyOrder(
                        "REQUESTED", "PROCESSING", "COMPLETED", "FAILED", "CANCELLED", "MANUAL_REVIEW")));
    }
}
