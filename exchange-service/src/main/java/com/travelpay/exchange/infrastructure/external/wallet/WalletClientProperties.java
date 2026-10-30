package com.travelpay.exchange.infrastructure.external.wallet;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * 지갑 서비스 호출 설정입니다. 타임아웃은 송금(payment-service)의 지갑 호출과 같게 둡니다.
 *
 * @param balancesUrl 내부 잔액 API 주소. 호스트는 Eureka 서비스 이름입니다.
 */
@Validated
@ConfigurationProperties("exchange.wallet")
public record WalletClientProperties(
        @NotNull @DefaultValue("http://wallet-service/internal/v1/wallets/balances") URI balancesUrl,
        @NotNull @DefaultValue("2s") Duration connectTimeout,
        @NotNull @DefaultValue("3s") Duration readTimeout
) {
}
