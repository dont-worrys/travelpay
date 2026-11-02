package com.travelpay.payment.infra.client.wallet;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.payment.client.wallet.Wallet;
import com.travelpay.payment.application.payment.client.wallet.WalletClient;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WalletFeignClient implements WalletClient {

    private static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";

    private final WalletFeignApi walletFeignApi;

    @Override
    public Optional<Wallet> findWallet(UUID userId) {
        ApiResponse<WalletFeignApi.WalletResponse> response;
        try {
            response = walletFeignApi.getWallet(userId);
        } catch (FeignException exception) {
            // 경로 오류 등으로 생기는 다른 404는 지갑 없음이 아니므로 WALLET_NOT_FOUND일 때만 empty로 본다
            if (FeignErrors.hasErrorCode(exception, WALLET_NOT_FOUND)) {
                return Optional.empty();
            }
            throw FeignErrors.toCommonException(exception);
        }

        if (response == null || !response.success() || response.data() == null
                || response.data().walletId() == null) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        return Optional.of(new Wallet(response.data().walletId(), response.data().status()));
    }
}
