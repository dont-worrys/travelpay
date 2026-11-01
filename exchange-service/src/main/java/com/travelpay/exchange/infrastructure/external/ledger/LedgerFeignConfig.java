package com.travelpay.exchange.infrastructure.external.ledger;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * 원장 클라이언트 전용 설정입니다. @Configuration을 붙이지 않아 다른 Feign 클라이언트에는 적용되지 않습니다.
 * 자동 재시도는 끕니다. 재시도는 선점한 흐름이 같은 요청을 다시 보내는 방식으로만 해서 원장 호출 횟수를 주문에 정확히 남깁니다.
 */
public class LedgerFeignConfig {

    @Bean
    public Retryer ledgerRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
