package com.travelpay.payment.transfer.client;

import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Wallet 서비스의 내부 지갑 조회 API입니다. 회원당 지갑은 1개이며, 통화별 계정은 원장이 관리합니다.
 * 응답은 ApiResponse로 감싼 지갑 ID·상태이며, 지갑이 없으면 404(WALLET_NOT_FOUND), userId 누락·형식 오류면 400입니다.
 * Wallet은 내부 경로에도 회원 헤더 검사를 하므로 User-Id·User-Role은 둘 다 보내지 않거나 둘 다 보내야 합니다.
 * 하나만 보내면 401이 납니다. 송금 요청 처리 중에 호출하므로 common 인터셉터가 송금인의 두 헤더를 함께 붙입니다.
 */
@FeignClient(name = "wallet-service", path = "/internal/v1/wallets")
public interface WalletClient {

    @GetMapping
    ApiResponse<WalletResponse> getWallet(@RequestParam("userId") UUID userId);
}
