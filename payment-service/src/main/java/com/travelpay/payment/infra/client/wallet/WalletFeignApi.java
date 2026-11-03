package com.travelpay.payment.infra.client.wallet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * 결제와 송금이 함께 사용합니다.
 */
@FeignClient(name = "wallet-service", contextId = "paymentWalletApi", path = "/internal/v1/wallets")
public interface WalletFeignApi {

    @GetMapping
    ApiResponse<WalletResponse> getWallet(@RequestParam("userId") UUID userId);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WalletResponse(UUID walletId, String status) {
    }
}
