package com.travelpay.exchange.infrastructure.external.ledger;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 원장 서비스의 거래 등록 내부 API입니다. url을 비우면 Eureka에서 ledger-service를 찾습니다.
 * 같은 reference(EXCHANGE + 주문 ID)는 한 번만 기록되고, 같은 내용으로 다시 보내면 처음 결과를 돌려줍니다.
 * 자동 재시도는 하지 않습니다(Feign 기본값). 재시도는 선점한 흐름이 같은 요청을 다시 보내는 방식으로만 해서
 * 원장 호출 횟수를 주문의 확인 횟수에 정확히 남깁니다.
 */
@FeignClient(name = "ledger-service", url = "${exchange.ledger.url:}", path = "/internal/v1/ledger/transactions")
public interface LedgerFeignClient {

    @PostMapping
    ApiResponse<LedgerTransactionResponse> register(@RequestBody RegisterLedgerTransactionRequest request);
}
