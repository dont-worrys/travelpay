package com.travelpay.exchange.domain.wallet;

/**
 * 지갑 서비스가 잔액을 원장에서 읽지 못해(원장 연결 실패·시간 초과·5xx) 지갑 잔액을 가져오지 못한 경우입니다.
 * 지갑 장애와 구분하려고 따로 둡니다. 잔액을 못 가져온 것은 같으므로 WalletBalanceFetchException을 상속해,
 * 둘을 구분하지 않는 쪽(한도 조회)은 기존 처리 그대로 동작합니다.
 */
public class WalletLedgerUnavailableException extends WalletBalanceFetchException {

    public WalletLedgerUnavailableException(String message) {
        super(message);
    }
}
