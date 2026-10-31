package com.travelpay.exchange.domain.wallet;

import java.util.Optional;
import java.util.UUID;

/**
 * 사용자 지갑의 통화별 잔액을 가져옵니다.
 * 환전은 이 인터페이스에만 의존하고, 지갑 서비스 호출 방법은 infrastructure 구현체가 정합니다.
 */
public interface WalletBalanceReader {

    /**
     * 사용자 지갑의 잔액과 상태를 가져옵니다. 지갑이 없으면 empty입니다.
     *
     * @throws WalletBalanceFetchException 지갑 서비스 호출이나 응답 해석에 실패한 경우
     */
    Optional<WalletBalances> read(UUID userId);
}
