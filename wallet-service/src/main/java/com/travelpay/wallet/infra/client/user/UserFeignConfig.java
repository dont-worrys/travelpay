package com.travelpay.wallet.infra.client.user;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * 회원 클라이언트 전용 설정. @Configuration을 붙이지 않아 다른 Feign 클라이언트에는 적용되지 않는다.
 * 자동 재시도는 끈다. 재시도는 호출자가 같은 요청(같은 Idempotency-Key)을 다시 보내는 방식으로만 한다.
 */
public class UserFeignConfig {

    @Bean
    public Retryer userRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
