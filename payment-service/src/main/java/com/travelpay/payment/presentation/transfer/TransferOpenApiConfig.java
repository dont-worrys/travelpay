package com.travelpay.payment.presentation.transfer;

import java.util.Arrays;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.web.CurrentUser;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.media.UUIDSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.models.GroupedOpenApi;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 송금 외부 API(/api/v1/transfers)만 묶은 API 문서 그룹입니다.
 * 문서 노출 여부는 springdoc 설정(prod에서 끔)을 따릅니다.
 */
@Configuration
public class TransferOpenApiConfig {

    private static final String SAMPLE_USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    static {
        // @CurrentUser 요청자는 요청 값이 아니라 회원 헤더에서 정해지므로 문서 파라미터에서 뺍니다.
        SpringDocUtils.getConfig().addAnnotationsToIgnore(CurrentUser.class);
    }

    @Bean
    public GroupedOpenApi transferOpenApi() {
        return GroupedOpenApi.builder()
                .group("transfer")
                .displayName("송금")
                .pathsToMatch("/api/v1/transfers", "/api/v1/transfers/**")
                .addOpenApiCustomizer(openApi -> openApi.info(new Info()
                        .title("TravelPay 송금 API")
                        .version("v1")
                        .description("""
                                사용자 간 같은 통화의 지갑 송금 API입니다.
                                금액은 통화별 최소 단위의 정수이며, 응답은 공통 형식 `{success, data, error}`를 따릅니다.
                                실제 환경에서는 Gateway가 세션을 확인한 뒤 회원 헤더를 붙이므로 클라이언트가 보내지 않습니다.
                                """)))
                // Gateway 없이 payment-service를 직접 호출할 때 입력하는 회원 헤더입니다.
                .addOperationCustomizer((operation, handlerMethod) -> operation
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
                                .example("USER")))
                .build();
    }
}
