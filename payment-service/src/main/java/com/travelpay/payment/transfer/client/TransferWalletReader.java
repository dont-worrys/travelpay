package com.travelpay.payment.transfer.client;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;

/**
 * 송금에 사용할 지갑을 Wallet 서비스에서 조회합니다.
 * 지갑 상태의 최종 판단은 원장이 하므로, 여기서는 원장까지 가기 전에 빨리 거절하는 용도로만 확인합니다.
 */
@Component
public class TransferWalletReader {

    private final WalletClient walletClient;

    public TransferWalletReader(WalletClient walletClient) {
        this.walletClient = walletClient;
    }

    /** 송금 가능한(ACTIVE) 지갑의 ID를 반환하고, 지갑이 없거나 송금할 수 없는 상태면 empty를 반환합니다. */
    public Optional<UUID> findActiveWalletId(UUID userId) {
        return findWallet(userId)
                .filter(WalletResponse::isActive)
                .map(WalletResponse::walletId);
    }

    /** 회원의 지갑을 상태와 관계없이 조회하며, 지갑이 없으면 empty를 반환합니다. */
    public Optional<WalletResponse> findWallet(UUID userId) {
        ApiResponse<WalletResponse> response;
        try {
            response = walletClient.getWallet(userId);
        } catch (FeignException.NotFound exception) {
            return Optional.empty();
        } catch (RetryableException exception) {
            // 조회 요청이라 실패해도 데이터 변경은 없으며, 연결·응답 시간 문제는 일시적 장애로 알립니다.
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        } catch (FeignException exception) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY, exception);
        }

        if (response == null || !response.success() || response.data() == null
                || response.data().walletId() == null) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        return Optional.of(response.data());
    }
}
