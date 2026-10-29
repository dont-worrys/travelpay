package com.travelpay.wallet.infra.client.ledger;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * 원장 클라이언트 전용 설정. @Configuration을 붙이지 않아 다른 Feign 클라이언트에는 적용되지 않는다.
 * 자동 재시도는 끈다: 원장 최대 응답(약 5초) × 재시도 횟수만큼 대기가 늘어 호출자 제한을 넘기기 때문.
 * 재시도는 호출자가 같은 요청을 다시 보내는 방식으로만 한다.
 */
public class LedgerFeignConfig {

    @Bean
    public Retryer ledgerRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
