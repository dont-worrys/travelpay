package com.travelpay.exchange.infrastructure.external.ledger;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 원장 서비스의 거래 등록 내부 API입니다. url을 비우면 Eureka에서 ledger-service를 찾습니다.
 * 같은 reference(EXCHANGE + 주문 ID)는 한 번만 기록되고, 같은 내용으로 다시 보내면 처음 결과를 돌려줍니다.
 */
@FeignClient(name = "ledger-service", url = "${exchange.ledger.url:}",
        path = "/internal/v1/ledger/transactions", configuration = LedgerFeignConfig.class)
public interface LedgerFeignClient {

    @PostMapping
    LedgerTransactionResponse register(@RequestBody RegisterLedgerTransactionRequest request);
}
