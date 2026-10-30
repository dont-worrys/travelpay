package com.travelpay.payment.infra.client.wallet;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

/**
 * 송금 쪽 클라이언트와 같은 서비스를 부르므로 contextId로 구분합니다.
 * 추후 리펙토링 예정입니다.
 */
@FeignClient(name = "wallet-service", contextId = "paymentWalletApi", path = "/internal/v1/wallets")
public interface WalletFeignApi {

    @GetMapping
    ApiResponse<WalletResponse> getWallet(@RequestParam("userId") UUID userId);

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WalletResponse(UUID walletId, String status) {
    }
}
