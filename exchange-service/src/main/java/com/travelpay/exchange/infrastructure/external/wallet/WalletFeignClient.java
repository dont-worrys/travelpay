package com.travelpay.exchange.infrastructure.external.wallet;

import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 지갑 서비스의 내부 지갑 API입니다. url을 비우면 Eureka에서 wallet-service를 찾습니다.
 * 지갑이 없으면 404(WALLET_NOT_FOUND)입니다. 누구의 지갑인지는 userId 파라미터로 정합니다.
 * 사용자 요청을 처리하는 중에 부르면 공통 Feign 설정이 그 요청의 User-Id·User-Role·Request-Id를 붙입니다.
 * 자동 재시도는 하지 않습니다(Feign 기본값). 한도 조회는 실패하면 한도만 응답하므로 재시도로 응답을 늦추지 않습니다.
 */
@FeignClient(name = "wallet-service", url = "${exchange.wallet.url:}", path = "/internal/v1/wallets")
public interface WalletFeignClient {

    @GetMapping("/balances")
    ApiResponse<WalletBalancesResponse> getBalances(@RequestParam("userId") UUID userId);
}
