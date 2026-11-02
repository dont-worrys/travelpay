package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

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
}
