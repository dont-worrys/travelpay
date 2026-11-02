package com.travelpay.payment.transfer.client;

import feign.Retryer;
import org.springframework.context.annotation.Bean;

/**
 * 송금 원장 클라이언트 전용 설정입니다. @Configuration을 붙이지 않아 다른 Feign 클라이언트에는 적용되지 않습니다.
 * 자동 재시도는 끕니다. 결과를 모르는 요청은 송금을 PROCESSING으로 남기고, 같은 Idempotency-Key 재요청 때
 * 같은 reference로 다시 보내는 방식으로만 재전송합니다(원장이 같은 reference를 한 번만 처리).
 */
public class TransferLedgerFeignConfig {

    @Bean
    public Retryer transferLedgerRetryer() {
        return Retryer.NEVER_RETRY;
    }
}
