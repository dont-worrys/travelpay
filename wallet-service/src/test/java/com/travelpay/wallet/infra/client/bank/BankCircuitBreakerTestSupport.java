package com.travelpay.wallet.infra.client.bank;

import java.time.Duration;

/** 테스트용 은행 서킷 설정. application.yml 값과 같다 */
final class BankCircuitBreakerTestSupport {

    private BankCircuitBreakerTestSupport() {
    }

    static BankCircuitBreakerProperties properties() {
        return new BankCircuitBreakerProperties(5, 5, 100f, Duration.ofSeconds(10), 1);
    }
}
