package com.travelpay.payment.infra.client.wallet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.payment.client.wallet.Wallet;
import com.travelpay.payment.application.payment.client.wallet.WalletClient;
import feign.FeignException;
import feign.RetryableException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WalletFeignClient implements WalletClient {

    private static final String WALLET_NOT_FOUND = "WALLET_NOT_FOUND";

    private final WalletFeignApi walletFeignApi;
    private final JsonMapper jsonMapper;

    @Override
    public Optional<Wallet> findWallet(UUID userId) {
        ApiResponse<WalletFeignApi.WalletResponse> response;
        try {
            response = walletFeignApi.getWallet(userId);
        } catch (FeignException.NotFound exception) {
            if (isWalletNotFound(exception)) {
                return Optional.empty();
            }
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY, exception);
        } catch (RetryableException | FeignException.ServiceUnavailable exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        } catch (FeignException exception) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY, exception);
        }

        if (response == null || !response.success() || response.data() == null
                || response.data().walletId() == null) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        return Optional.of(new Wallet(response.data().walletId(), response.data().status()));
    }

    private boolean isWalletNotFound(FeignException exception) {
        try {
            ErrorBody body = jsonMapper.readValue(exception.contentUTF8(), ErrorBody.class);
            return body != null && body.error() != null && WALLET_NOT_FOUND.equals(body.error().code());
        } catch (JacksonException | IllegalArgumentException ignored) {
            return false;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(ErrorCodeBody error) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorCodeBody(String code) {
    }
}
