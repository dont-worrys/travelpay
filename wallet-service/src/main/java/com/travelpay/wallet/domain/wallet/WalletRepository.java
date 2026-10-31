package com.travelpay.wallet.domain.wallet;

import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    /** 회원 지갑이 없을 때만 PENDING으로 추가한다. 동시 요청에도 1건만 생긴다 */
    void addIfAbsent(UUID userId);

    Optional<Wallet> findByUserId(UUID userId);

    /** 행 잠금(FOR UPDATE)으로 조회한다. 트랜잭션 안에서만 사용 */
    Optional<Wallet> findByUserIdForUpdate(UUID userId);
}
