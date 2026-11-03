package com.travelpay.payment.application.transfer.client;

import java.util.Optional;
import java.util.UUID;

/**
 * 송금에 사용할 회원의 지갑을 조회합니다.
 * 지갑 상태의 최종 판단은 원장이 하므로, 원장까지 가기 전에 빨리 거절하는 용도로만 확인합니다.
 * 지갑 서비스 장애는 common 오류(502·503·504)로 던집니다.
 */
public interface TransferWalletReader {

    /** 회원의 지갑을 상태와 관계없이 조회하며, 지갑이 없으면 empty를 반환합니다. */
    Optional<TransferWallet> findWallet(UUID userId);

    /** 송금 가능한(ACTIVE) 지갑의 ID를 반환하고, 지갑이 없거나 송금할 수 없는 상태면 empty를 반환합니다. */
    default Optional<UUID> findActiveWalletId(UUID userId) {
        return findWallet(userId)
                .filter(TransferWallet::isActive)
                .map(TransferWallet::walletId);
    }
}
