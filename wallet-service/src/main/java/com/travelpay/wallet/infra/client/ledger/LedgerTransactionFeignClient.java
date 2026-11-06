package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.UUID;

/**
 * 원장 거래 등록·조회 내부 API. 계정 클라이언트와 같은 서비스라 contextId로 빈·설정 키를 나눈다.
 * url을 비우면 Eureka에서 ledger-service를 찾는다.
 * TODO: 서비스 간 내부 인증 헤더 규칙이 정해지면 추가
 */
@FeignClient(name = "ledger-service", contextId = "ledger-transaction", url = "${travelpay.client.ledger.url:}",
        path = "/internal/v1/ledger/transactions", configuration = LedgerFeignConfig.class)
public interface LedgerTransactionFeignClient {

    @PostMapping
    ApiResponse<LedgerTransactionResponse> register(@RequestBody RegisterLedgerTransactionRequest request);

    /** reference 단건. 없으면 404 LEDGER_TRANSACTION_NOT_FOUND */
    @GetMapping
    ApiResponse<LedgerTransactionDetailResponse> findByReference(@RequestParam("referenceType") String referenceType,
                                                                 @RequestParam("referenceId") UUID referenceId);

    /** 기간 [from, to) 목록. cursor가 null이면 첫 페이지, 응답 nextCursor가 null이면 마지막 페이지 */
    @GetMapping("/period")
    ApiResponse<LedgerTransactionPeriodResponse> listByPeriod(@RequestParam("referenceType") String referenceType,
                                                              @RequestParam("from") Instant from,
                                                              @RequestParam("to") Instant to,
                                                              @RequestParam(value = "cursor", required = false)
                                                              String cursor,
                                                              @RequestParam("size") int size);
}
