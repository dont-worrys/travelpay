package com.travelpay.common.config;

import com.travelpay.common.auth.CurrentUserProvider;
import com.travelpay.common.feign.InternalHeaderRelayInterceptor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * Feign을 쓰는 MVC 서비스에 내부 헤더 전달({@link InternalHeaderRelayInterceptor})을 자동으로 등록합니다.
 * 등록된 인터셉터는 그 서비스의 모든 Feign 클라이언트에 적용됩니다.
 */
@AutoConfiguration(after = CommonAuthWebAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass(name = {
        "feign.RequestInterceptor",
        "jakarta.servlet.http.HttpServletRequest"
})
public class CommonFeignAutoConfiguration {

    @Bean
    @ConditionalOnBean(CurrentUserProvider.class)
    @ConditionalOnMissingBean(InternalHeaderRelayInterceptor.class)
    public InternalHeaderRelayInterceptor internalHeaderRelayInterceptor(CurrentUserProvider currentUserProvider) {
        return new InternalHeaderRelayInterceptor(currentUserProvider);
    }
}
