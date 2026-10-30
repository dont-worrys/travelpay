package com.travelpay.exchange.infrastructure.external.wallet;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * 지갑 클라이언트 전용 설정입니다. @Configuration을 붙이지 않아 다른 Feign 클라이언트에는 적용되지 않습니다.
 * 자동 재시도는 끕니다. 한도 조회는 실패하면 한도만 응답하므로, 재시도로 응답을 늦추지 않습니다.
 */
public class WalletFeignConfig {

    @Bean
    public Retryer walletRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
