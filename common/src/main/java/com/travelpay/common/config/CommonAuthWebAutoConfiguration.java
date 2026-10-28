package com.travelpay.common.config;

import java.util.List;

import com.travelpay.common.auth.CurrentUserProvider;
import com.travelpay.common.auth.web.CurrentUserArgumentResolver;
import com.travelpay.common.auth.web.CurrentUserHeaderInterceptor;
import com.travelpay.common.auth.web.RequestAttributeCurrentUserProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 요청자 정보 조회와 파라미터 전달을 등록합니다.
 * travelpay.auth.header-context.enabled=true이면 헤더를 요청 속성에 저장하는 연결도 등록합니다.
 * 서비스 인증과 세션 검증은 수행하지 않습니다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "jakarta.servlet.http.HttpServletRequest",
        "org.springframework.web.servlet.config.annotation.WebMvcConfigurer"
})
public class CommonAuthWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CurrentUserProvider.class)
    public CurrentUserProvider currentUserProvider() {
        return new RequestAttributeCurrentUserProvider();
    }

    @Bean
    @ConditionalOnMissingBean(CurrentUserArgumentResolver.class)
    public CurrentUserArgumentResolver currentUserArgumentResolver(CurrentUserProvider currentUserProvider) {
        return new CurrentUserArgumentResolver(currentUserProvider);
    }

    @Bean
    public WebMvcConfigurer commonAuthWebMvcConfigurer(CurrentUserArgumentResolver currentUserArgumentResolver) {
        return new WebMvcConfigurer() {
            @Override
            public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
                resolvers.add(currentUserArgumentResolver);
            }
        };
    }

    @Bean
    @ConditionalOnProperty(prefix = "travelpay.auth.header-context", name = "enabled", havingValue = "true")
    @ConditionalOnMissingBean(CurrentUserHeaderInterceptor.class)
    public CurrentUserHeaderInterceptor currentUserHeaderInterceptor() {
        return new CurrentUserHeaderInterceptor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "travelpay.auth.header-context", name = "enabled", havingValue = "true")
    public WebMvcConfigurer commonAuthHeaderWebMvcConfigurer(CurrentUserHeaderInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }
}
