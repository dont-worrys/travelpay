package com.travelpay.payment.infra.client.transfer;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.transfer.client.TransferWallet;
import com.travelpay.payment.application.transfer.client.TransferWalletReader;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * 송금에 사용할 지갑을 Wallet 서비스에서 조회합니다.
 * WALLET_NOT_FOUND만 지갑 없음으로 보고, 그 밖의 실패는 common 규칙({@link FeignErrors#toCommonException})을 따릅니다.
 */
@Component
public class TransferWalletReaderAdapter implements TransferWalletReader {

    private static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";

    private final WalletClient walletClient;

    public TransferWalletReaderAdapter(WalletClient walletClient) {
        this.walletClient = walletClient;
    }

    /**
     * 회원의 지갑을 상태와 관계없이 조회하며, 지갑이 없으면 empty를 반환합니다.
     * 경로 오류·배포 문제로 생기는 404(COMMON_NOT_FOUND 등)는 지갑 없음이 아니므로 WALLET_NOT_FOUND일 때만 empty로 봅니다.
     */
    @Override
    public Optional<TransferWallet> findWallet(UUID userId) {
        ApiResponse<WalletResponse> response;
        try {
            response = walletClient.getWallet(userId);
        } catch (FeignException exception) {
            if (FeignErrors.hasErrorCode(exception, WALLET_NOT_FOUND)) {
                return Optional.empty();
            }
            throw FeignErrors.toCommonException(exception);
        }

        if (response == null || !response.success() || response.data() == null
                || response.data().walletId() == null) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        return Optional.of(new TransferWallet(response.data().walletId(), response.data().status()));
    }
}
