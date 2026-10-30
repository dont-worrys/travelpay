package com.travelpay.exchange.infrastructure.external.wallet;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 지갑 내부 잔액 API(GET /internal/v1/wallets/balances) 응답입니다. 공통 응답 형식 ApiResponse{success, data, error}를 따릅니다.
 * 상태는 지갑 서비스가 정하는 값(PENDING, ACTIVE, SUSPENDED, CLOSED)이라 문자열로 받습니다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record WalletBalancesResponse(Data data, Error error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(UUID walletId, String status, List<Balance> balances) {
    }

    /** 잔액은 통화 최소 단위 정수입니다. (CNY 3520 = 35.20위안) */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Balance(String currency, Long balance, String status) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Error(String code) {
    }
}
