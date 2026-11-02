package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.UUID;

/**
 * 원장 지갑 계정 내부 API. url을 비우면 Eureka에서 ledger-service를 찾는다.
 * TODO: 서비스 간 내부 인증 헤더 규칙이 정해지면 추가
 */
@FeignClient(name = "ledger-service", url = "${travelpay.client.ledger.url:}",
        path = "/internal/v1/ledger/accounts", configuration = LedgerFeignConfig.class)
public interface LedgerFeignClient {

    @PostMapping
    ApiResponse<List<LedgerAccountResponse>> openWalletAccounts(@RequestBody OpenLedgerAccountsRequest request);

    @GetMapping
    ApiResponse<List<LedgerAccountResponse>> getWalletAccounts(@RequestParam("walletId") UUID walletId);
}
