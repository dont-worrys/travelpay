package com.travelpay.payment.presentation.config;

import java.util.Arrays;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 결제 외부 API(/api/v1/payments: 결제·환불·가맹점·정산)와 결제 관리자 API(/api/v1/admin/payments)의 API 문서 그룹입니다.
 * 송금 문서 그룹(TransferOpenApiConfig)과 같은 방식이며, 문서 노출 여부는 springdoc 설정(prod에서 끔)을 따릅니다.
 * 내부 API(/internal/**)는 경로 조건에 들어가지 않아 문서에 나오지 않습니다.
 */
@Configuration
public class PaymentOpenApiConfig {

    private static final String SAMPLE_USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Bean
    public GroupedOpenApi paymentOpenApi() {
        return GroupedOpenApi.builder()
                .group("payment")
                .displayName("결제")
                .pathsToMatch("/api/v1/payments", "/api/v1/payments/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 결제 API")
                        .version("v1")
                        .description("""
                                지갑 잔액으로 가맹점에 결제하고, 고객 환불 신청·가맹점 환불 실행, 가맹점 생성·잔액, 정산을 다루는 API입니다.
                                금액은 통화별 최소 단위의 정수(KRW 원, JPY 엔, CNY 펀)이며, 응답은 공통 형식 `{success, data, error}`를 따릅니다.
                                실제 환경에서는 Gateway가 세션을 확인한 뒤 회원 헤더를 붙이므로 클라이언트가 보내지 않습니다.
                                자세한 처리 규칙은 docs/guide/payment.md를 봅니다.
                                """)))
                .addOperationCustomizer(authHeaders("USER"))
                .build();
    }

    /** 결제 관리자 API(/api/v1/admin/payments)는 사용자 API와 경로·권한이 달라 별도 그룹으로 둡니다. */
    @Bean
    public GroupedOpenApi paymentAdminOpenApi() {
        return GroupedOpenApi.builder()
                .group("payment-admin")
                .displayName("결제 관리")
                .pathsToMatch("/api/v1/admin/payments", "/api/v1/admin/payments/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 결제 관리 API")
                        .version("v1")
                        .description("""
                                관리자(MASTER)의 MANUAL_REVIEW 결제·환불·정산 처리, 처리 기록, 오래된 환불 신청 조회 API입니다. MASTER가 아니면 403입니다.
                                처리 결과는 운영자가 고르지 않고 서버가 원장 조회 결과로 정합니다.
                                실제 환경에서는 Gateway가 세션을 확인한 뒤 회원 헤더를 붙이므로 클라이언트가 보내지 않습니다.
                                """)))
                .addOperationCustomizer(authHeaders("MASTER"))
                .build();
    }

    /** Gateway 없이 payment-service를 직접 호출할 때 입력하는 회원 헤더(User-Id·User-Role)를 모든 API 문서에 추가합니다. */
    private static OperationCustomizer authHeaders(String sampleRole) {
        return (operation, handlerMethod) -> operation
                .addParametersItem(new HeaderParameter()
                        .name(AuthHeaders.USER_ID)
                        .required(true)
                        .description("요청자 회원 ID. Gateway가 세션 확인 후 전달합니다(직접 호출 테스트용).")
                        .schema(new UUIDSchema())
                        .example(SAMPLE_USER_ID))
                .addParametersItem(new HeaderParameter()
                        .name(AuthHeaders.USER_ROLE)
                        .required(true)
                        .description("요청자 역할. Gateway가 세션 확인 후 전달합니다(직접 호출 테스트용).")
                        .schema(new StringSchema()._enum(Arrays.stream(AuthRole.values()).map(Enum::name).toList()))
                        .example(sampleRole));
    }
}
