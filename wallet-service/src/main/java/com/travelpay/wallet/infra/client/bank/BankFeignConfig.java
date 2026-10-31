package com.travelpay.wallet.infra.client.bank;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * 은행 클라이언트 전용 설정. @Configuration을 붙이지 않아 다른 Feign 클라이언트에는 적용되지 않는다.
 * 자동 재시도는 끈다: 재시도는 SAGA가 같은 requestId로 다시 보내는 방식으로만 한다.
 */
public class BankFeignConfig {

    @Bean
    public Retryer bankRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
