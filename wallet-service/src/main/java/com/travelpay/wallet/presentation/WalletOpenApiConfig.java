package com.travelpay.wallet.presentation;

import java.util.Arrays;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.web.CurrentUser;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 지갑·충전 외부 API(/api/v1/**, 관리자 경로 제외)와 관리자 충전 API(/api/v1/admin/charges/**)의 API 문서 그룹입니다.
 * 내부 API(/internal/**)는 문서화하지 않습니다. 문서 노출 여부는 springdoc 설정(prod에서 끔)을 따릅니다.
 */
@Configuration
public class WalletOpenApiConfig {

    private static final String SAMPLE_USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    static {
        // @CurrentUser 요청자는 요청 값이 아니라 회원 헤더에서 정해지므로 문서 파라미터에서 뺍니다.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    @Bean
    public GroupedOpenApi walletOpenApi() {
        return GroupedOpenApi.builder()
                .group("wallet")
                .displayName("지갑·충전")
                .pathsToMatch("/api/v1/**")
                .pathsToExclude("/api/v1/admin/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 지갑·충전 API")
                        .version("v1")
                        .description("""
                                회원(USER)의 지갑 개설·조회와 계좌 충전 API입니다. MASTER는 403입니다.
                                금액은 통화별 최소 단위의 정수이며, 응답은 공통 형식 `{success, data, error}`를 따릅니다.
                                실제 환경에서는 Gateway가 세션을 확인한 뒤 회원 헤더를 붙이므로 클라이언트가 보내지 않습니다.
                                """)))
                .addOperationCustomizer(authHeaders("USER"))
                .build();
    }

    /** 관리자 충전 API(/api/v1/admin/charges)는 회원 API와 경로·권한이 달라 별도 그룹으로 둡니다. */
    @Bean
    public GroupedOpenApi chargeAdminOpenApi() {
        return GroupedOpenApi.builder()
                .group("charge-admin")
                .displayName("충전 관리")
                .pathsToMatch("/api/v1/admin/charges", "/api/v1/admin/charges/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 충전 관리 API")
                        .version("v1")
                        .description("""
                                관리자(MASTER)의 충전 조회·MANUAL_REVIEW 보정과 충전 대사 실행·조회 API입니다.
                                `User-Role`이 MASTER가 아니면 403입니다.
                                실제 환경에서는 Gateway가 세션을 확인한 뒤 회원 헤더를 붙이므로 클라이언트가 보내지 않습니다.
                                """)))
                .addOperationCustomizer(authHeaders("MASTER"))
                .build();
    }

    /** Gateway 없이 wallet-service를 직접 호출할 때 입력하는 회원 헤더(User-Id·User-Role)를 모든 API 문서에 추가합니다. */
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
