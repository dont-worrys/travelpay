package com.travelpay.user.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 관리자 API의 변경(정지·해제·세션 종료·약관 등록)과 조회에서 DB 작업·잠금 대기에 적용할 트랜잭션 제한 시간입니다. */
@ConfigurationProperties("travelpay.user.admin")
public record AdminProperties(Duration transactionTimeout) {

    public AdminProperties {
        if (transactionTimeout == null || transactionTimeout.isNegative() || transactionTimeout.isZero()
                || transactionTimeout.getNano() != 0 || transactionTimeout.getSeconds() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "travelpay.user.admin.transaction-timeout은 단위를 붙인 1초 이상의 정수 초여야 합니다(예: 5s).");
        }
    }
}
