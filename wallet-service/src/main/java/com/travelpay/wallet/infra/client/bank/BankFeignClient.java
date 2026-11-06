package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

/**
 * Mock 은행 내부 API. url을 비우면 Eureka에서 mock-bank를 찾는다.
 * TODO: 서비스 간 내부 인증 헤더 규칙이 정해지면 추가
 */
@FeignClient(name = "mock-bank", url = "${travelpay.client.bank.url:}",
        path = "/internal/v1/bank", configuration = BankFeignConfig.class)
public interface BankFeignClient {

    /** @param fault Mock-Fault 헤더 값. null이면 헤더를 보내지 않는다 (BankFaultInjection 참고) */
    @PostMapping("/withdrawals")
    ApiResponse<BankTransactionResponse> withdraw(
            @RequestHeader(value = BankFaultInjection.HEADER, required = false) String fault,
            @RequestBody BankWithdrawalRequest request);

    @PostMapping("/refunds")
    ApiResponse<BankTransactionResponse> refund(@RequestBody BankRefundRequest request);

    /** 기간 [from, to) 거래 목록 (created_at 오름차순, page는 0부터, size 최대 100) */
    @GetMapping("/transactions")
    ApiResponse<PageResponse<BankTransactionDetailResponse>> listTransactions(@RequestParam("from") Instant from,
                                                                             @RequestParam("to") Instant to,
                                                                             @RequestParam("page") int page,
                                                                             @RequestParam("size") int size);

    /** requestId 단건. 없으면 404 BANK_TRANSACTION_NOT_FOUND */
    @GetMapping("/transactions/{requestId}")
    ApiResponse<BankTransactionDetailResponse> findTransaction(@PathVariable("requestId") String requestId);
}
