package com.travelpay.wallet.infra.client.ledger;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 원장 거래 등록 내부 API. 계정 클라이언트와 같은 서비스라 contextId로 빈·설정 키를 나눈다.
 * url을 비우면 Eureka에서 ledger-service를 찾는다.
 * TODO: 서비스 간 내부 인증 헤더 규칙이 정해지면 추가
 */
@FeignClient(name = "ledger-service", contextId = "ledger-transaction", url = "${travelpay.client.ledger.url:}",
        path = "/internal/v1/ledger/transactions", configuration = LedgerFeignConfig.class)
public interface LedgerTransactionFeignClient {

    @PostMapping
    LedgerApiResponse<LedgerTransactionResponse> register(@RequestBody RegisterLedgerTransactionRequest request);
}
