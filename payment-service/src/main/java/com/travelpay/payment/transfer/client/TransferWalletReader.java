package com.travelpay.payment.transfer.client;

import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import feign.FeignException;
import feign.RetryableException;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * 송금에 사용할 지갑을 Wallet 서비스에서 조회합니다.
 * 지갑 상태의 최종 판단은 원장이 하므로, 여기서는 원장까지 가기 전에 빨리 거절하는 용도로만 확인합니다.
 */
@Component
public class TransferWalletReader {

    private static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";

    private final WalletClient walletClient;
    private final JsonMapper jsonMapper;

    public TransferWalletReader(WalletClient walletClient, JsonMapper jsonMapper) {
        this.walletClient = walletClient;
        this.jsonMapper = jsonMapper;
    }

    /** 송금 가능한(ACTIVE) 지갑의 ID를 반환하고, 지갑이 없거나 송금할 수 없는 상태면 empty를 반환합니다. */
    public Optional<UUID> findActiveWalletId(UUID userId) {
        return findWallet(userId)
                .filter(WalletResponse::isActive)
                .map(WalletResponse::walletId);
    }

    /**
     * 회원의 지갑을 상태와 관계없이 조회하며, 지갑이 없으면 empty를 반환합니다.
     * 경로 오류·배포 문제로 생기는 404(COMMON_NOT_FOUND 등)는 지갑 없음이 아니므로 WALLET_NOT_FOUND일 때만 empty로 봅니다.
     */
    public Optional<WalletResponse> findWallet(UUID userId) {
        ApiResponse<WalletResponse> response;
        try {
            response = walletClient.getWallet(userId);
        } catch (FeignException.NotFound exception) {
            if (isWalletNotFound(exception)) {
                return Optional.empty();
            }
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY, exception);
        } catch (RetryableException | FeignException.ServiceUnavailable exception) {
            // 조회 요청이라 실패해도 데이터 변경은 없으며, 연결·응답 시간 문제는 일시적 장애로 알립니다.
            // Eureka에 인스턴스가 없을 때 로드밸런서가 만드는 503과 Wallet 서비스의 503도 같은 일시적 장애로 봅니다.
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

    private boolean isWalletNotFound(FeignException exception) {
        try {
            ErrorBody body = jsonMapper.readValue(exception.contentUTF8(), ErrorBody.class);
            return body != null && body.error() != null && WALLET_NOT_FOUND.equals(body.error().code());
        } catch (JacksonException | IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 404 응답에서 오류 코드만 읽습니다. 공통 ErrorResponse는 details가 없으면 생성되지 않아 따로 둡니다. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(ErrorCodeBody error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorCodeBody(String code) {
    }
}
