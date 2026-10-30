package com.travelpay.exchange.infrastructure.external.wallet;

import java.util.UUID;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 지갑 서비스의 내부 지갑 API입니다. url을 비우면 Eureka에서 wallet-service를 찾습니다.
 * 지갑이 없으면 404(WALLET_NOT_FOUND)입니다.
 * 지갑 서비스는 내부 경로에도 회원 헤더를 검사하므로 User-Id·User-Role은 둘 다 보내지 않습니다.
 */
@FeignClient(name = "wallet-service", url = "${exchange.wallet.url:}",
        path = "/internal/v1/wallets", configuration = WalletFeignConfig.class)
public interface WalletFeignClient {

    @GetMapping("/balances")
    WalletBalancesResponse getBalances(@RequestParam("userId") UUID userId);
}
