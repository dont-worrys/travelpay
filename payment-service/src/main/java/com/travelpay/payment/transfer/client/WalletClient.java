package com.travelpay.payment.transfer.client;

import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Wallet 서비스의 내부 지갑 조회 API입니다. 회원당 지갑은 1개이며, 통화별 계정은 원장이 관리합니다.
 * 경로와 반환 정보(지갑 ID, 지갑 상태)는 Wallet 담당자와 합의했습니다.
 * TODO(wallet-service): 응답 필드 이름(walletId, status)과 ApiResponse 감싸기, 지갑이 없을 때 404 여부는
 *  가정입니다. Wallet API가 제공되면 확인하고 맞춥니다.
 */
@FeignClient(name = "wallet-service", path = "/internal/v1/wallets")
public interface WalletClient {

    @GetMapping
    ApiResponse<WalletResponse> getWallet(@RequestParam("userId") UUID userId);
}
