package com.travelpay.ledger.presentation.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.EnumFeature;

/**
 * 원장 HTTP 요청 본문의 enum에 숫자(0)나 숫자 문자열("0")이 오면 ordinal로 받지 않고 400으로 거절한다.
 * 배포는 application-prod.yml만 읽으므로 yml 속성이 아니라 빈으로 둔다.
 */
@Configuration(proxyBeanMethods = false)
public class LedgerJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer rejectNumericEnums() {
        return builder -> builder.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }
}
