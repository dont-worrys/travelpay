package com.travelpay.gateway.request;

import java.net.URI;
import java.util.List;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.gateway.config.GlobalCorsProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;

/** CORS 처리는 Gateway 기본 기능에 맡기고, 허용 출처 설정과 거절 응답 형식을 맞춥니다. */
@Configuration(proxyBeanMethods = false)
class GatewayCorsConfiguration {

    GatewayCorsConfiguration(GlobalCorsProperties properties) {
        for (var configuration : properties.getCorsConfigurations().values()) {
            if (configuration.getAllowedOriginPatterns() != null && !configuration.getAllowedOriginPatterns().isEmpty()) {
                throw invalidOrigin();
            }
            var origins = configuration.getAllowedOrigins();
            if (origins != null) {
                origins.forEach(GatewayCorsConfiguration::requireExactOrigin);
            }
            configuration.validateAllowCredentials();
        }
    }

    private static void requireExactOrigin(String origin) {
        try {
            var uri = URI.create(origin);
            var host = uri.getHost();
            var loopback = host != null && List.of("localhost", "127.0.0.1", "[::1]").stream()
                    .anyMatch(host::equalsIgnoreCase);
            var allowedScheme = "https".equalsIgnoreCase(uri.getScheme())
                    || loopback && "http".equalsIgnoreCase(uri.getScheme());
            if (host != null && allowedScheme && uri.getRawUserInfo() == null
                    && (uri.getRawPath() == null || uri.getRawPath().isEmpty())
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535)) {
                return;
            }
        }
        catch (IllegalArgumentException ignored) {
            // 입력 주소를 예외에 넣지 않고, 설정 이름과 허용 형식만 안내합니다.
        }
        throw invalidOrigin();
    }

    private static IllegalArgumentException invalidOrigin() {
        return new IllegalArgumentException("Gateway globalcors 허용 출처는 HTTPS 주소 또는 HTTP localhost 주소여야 합니다. "
                + "와일드카드·경로·인증 정보·쿼리·프래그먼트는 사용할 수 없습니다.");
    }

    @Bean
    static BeanPostProcessor gatewayCorsErrorResponses() {
        // 프레임워크의 출처·메소드·헤더 검사는 유지하고, 거절만 기존 JSON 오류 처리기로 전달합니다.
        var processor = new DefaultCorsProcessor() {
            @Override
            protected void rejectRequest(ServerHttpResponse response) {
                throw new BusinessException(CommonErrorCode.FORBIDDEN);
            }
        };
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof AbstractHandlerMapping mapping) {
                    mapping.setCorsProcessor(processor);
                }
                return bean;
            }
        };
    }
}
