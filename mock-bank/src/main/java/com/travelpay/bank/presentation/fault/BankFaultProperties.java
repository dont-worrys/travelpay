package com.travelpay.bank.presentation.fault;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** delay: Mock-Fault: DELAY일 때 처리·커밋 후 응답을 늦추는 시간 */
@ConfigurationProperties("mock-bank.fault")
public record BankFaultProperties(@DefaultValue("10s") Duration delay) {
}
