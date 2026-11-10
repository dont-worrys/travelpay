package com.travelpay.bank.presentation.fault;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * withdrawalLostRate: 출금 정상 응답(2xx)을 처리·커밋 후 버리고 500으로 바꾸는 비율 (0.0~1.0, 0이면 끔).
 * 범위 밖이면 기동 실패. mock-bank.fault.enabled=true가 아니거나 prod면 빈이 없어 읽지도 검증하지도 않는다.
 */
@Profile("!prod")
@ConditionalOnProperty(prefix = "mock-bank.fault", name = "enabled", havingValue = "true", matchIfMissing = false)
@Validated
@ConfigurationProperties("mock-bank.fault")
public record BankWithdrawalLostRateProperties(
        @DecimalMin("0.0") @DecimalMax("1.0") @DefaultValue("0") double withdrawalLostRate) {
}
