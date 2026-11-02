package com.travelpay.exchange.infrastructure.external.wallet;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 지갑 내부 잔액 API(GET /internal/v1/wallets/balances) 응답의 data입니다. 바깥 형식 {success, data, error}는 공통 ApiResponse로 받습니다.
 * 상태는 지갑 서비스가 정하는 값(PENDING, ACTIVE, SUSPENDED, CLOSED)이라 문자열로 받습니다.
 * Feign 프록시는 다른 패키지에 만들어지므로, 반환 타입인 이 record는 public이어야 합니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WalletBalancesResponse(UUID walletId, String status, List<Balance> balances) {

    /** 잔액은 통화 최소 단위 정수입니다. (CNY 3520 = 35.20위안) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Balance(String currency, Long balance, String status) {
    }
}
