package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.response.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Mock 은행 내부 API. url을 비우면 Eureka에서 mock-bank를 찾는다.
 * TODO: 서비스 간 내부 인증 헤더 규칙이 정해지면 추가
 */
@FeignClient(name = "mock-bank", url = "${travelpay.client.bank.url:}",
        path = "/internal/v1/bank", configuration = BankFeignConfig.class)
public interface BankFeignClient {

    @PostMapping("/withdrawals")
    ApiResponse<BankTransactionResponse> withdraw(@RequestBody BankWithdrawalRequest request);

    @PostMapping("/refunds")
    ApiResponse<BankTransactionResponse> refund(@RequestBody BankRefundRequest request);
}
