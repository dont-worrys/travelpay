package com.travelpay.exchange.infrastructure.config;

import java.util.Arrays;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.web.CurrentUser;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.web.method.HandlerMethod;

/**
 * 환전 서비스 API 문서(Swagger) 설정입니다. 팀 공통 방식(payment-service의 TransferOpenApiConfig)을 따릅니다.
 * 로컬에서만 켜며(application.yml), 운영에서는 끕니다(application-prod.yml).
 *
 * - exchange(환전): 환율·한도·견적·환전 신청/조회 API
 * - exchange-admin(환전 관리): MANUAL_REVIEW 처리 등 관리자(MASTER) API
 *
 * 회원 헤더(User-Id·User-Role)는 요청자를 쓰는(@CurrentUser) API에만 붙입니다.
 * 환율 조회처럼 로그인 없이 쓰는 공개 API에 붙이면 문서가 실제와 달라지기 때문입니다.
 */
@Configuration(proxyBeanMethods = false)
public class ExchangeOpenApiConfig {

    static final String EXCHANGE_GROUP = "exchange";
    static final String ADMIN_GROUP = "exchange-admin";

    private static final String SAMPLE_USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    static {
        // @CurrentUser 요청자는 요청 값이 아니라 회원 헤더에서 정해지므로 문서 파라미터에서 뺍니다.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    @Bean
    public GroupedOpenApi exchangeOpenApi() {
        return GroupedOpenApi.builder()
                .group(EXCHANGE_GROUP)
                .displayName("환전")
                .pathsToMatch("/api/v1/exchange-rates", "/api/v1/exchange-rates/**",
                        "/api/v1/exchange-limits", "/api/v1/exchange-limits/**",
                        "/api/v1/exchange-quotes", "/api/v1/exchange-quotes/**",
                        "/api/v1/exchanges", "/api/v1/exchanges/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 환전 API")
                        .version("v1")
                        .description("""
                                원화 ↔ 외화(JPY·CNY) 환전 API입니다. 환율 조회 → 견적 발급(5분 유효) → 환전 신청 순서로 씁니다.
                                금액은 통화 단위 문자열(예: CNY "100.50")이고, 응답은 공통 형식 `{success, data, error}`를 따릅니다.
                                환율 조회는 로그인 없이 쓸 수 있고, 나머지는 실제 환경에서 Gateway가 세션을 확인한 뒤 회원 헤더를 붙입니다.
                                """)))
                .addOperationCustomizer(memberHeaders("USER"))
                .addOperationCustomizer(idempotencyKeyRequired())
                .build();
    }

    /** 관리자 API(/api/v1/admin/exchanges)는 사용자 API와 경로·권한이 달라 별도 그룹으로 둡니다. */
    @Bean
    public GroupedOpenApi exchangeAdminOpenApi() {
        return GroupedOpenApi.builder()
                .group(ADMIN_GROUP)
                .displayName("환전 관리")
                .pathsToMatch("/api/v1/admin/exchanges", "/api/v1/admin/exchanges/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 환전 관리 API")
                        .version("v1")
                        .description("""
                                관리자(MASTER)의 MANUAL_REVIEW 환전 주문 조회·처리와 처리 이력 API입니다. MASTER가 아니면 403입니다.
                                처리 결과는 관리자가 고르지 않고, 서버가 원장을 조회해 정합니다.
                                """)))
                .addOperationCustomizer(memberHeaders("MASTER"))
                .build();
    }

    /**
     * 요청자를 쓰는 API(@CurrentUser 파라미터가 있는 메서드)에만 회원 헤더를 문서로 붙입니다.
     * Gateway 없이 환전 서비스를 직접 호출해 볼 때 입력하는 값입니다.
     */
    private static OperationCustomizer memberHeaders(String sampleRole) {
        return (operation, handlerMethod) -> {
            if (!usesCurrentUser(handlerMethod)) {
                return operation;
            }
            return operation
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
        };
    }

    /**
     * 환전 신청의 Idempotency-Key 헤더를 필수로 표시합니다.
     * 컨트롤러는 헤더가 없을 때 공통 400 대신 EXCHANGE_INVALID_IDEMPOTENCY_KEY로 답하려고 required=false로 받으므로,
     * 그대로 두면 문서에 선택 항목으로 보입니다.
     */
    private static OperationCustomizer idempotencyKeyRequired() {
        return (operation, handlerMethod) -> {
            if (operation.getParameters() == null) {
                return operation;
            }
            for (Parameter parameter : operation.getParameters()) {
                if (IDEMPOTENCY_KEY_HEADER.equals(parameter.getName()) && "header".equals(parameter.getIn())) {
                    parameter.required(true).description("""
                            같은 신청을 다시 보낼 때 쓰는 키(공백·제어 문자를 뺀 ASCII 1~100자).
                            같은 키로 다시 보내면 새 주문을 만들지 않고 처음 주문으로 응답합니다.""");
                }
            }
            return operation;
        };
    }

    private static boolean usesCurrentUser(HandlerMethod handlerMethod) {
        return Arrays.stream(handlerMethod.getMethodParameters())
                .anyMatch((MethodParameter parameter) -> parameter.hasParameterAnnotation(CurrentUser.class));
    }
}
