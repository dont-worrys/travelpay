package com.travelpay.bank.presentation.transaction;

import com.travelpay.bank.application.transaction.BankRefundService;
import com.travelpay.bank.application.transaction.BankTransactionQueryService;
import com.travelpay.bank.application.transaction.BankWithdrawalService;
import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.domain.transaction.BankTransactionStatus;
import com.travelpay.bank.presentation.transaction.dto.BankRefundRequest;
import com.travelpay.bank.presentation.transaction.dto.BankTransactionResponse;
import com.travelpay.bank.presentation.transaction.dto.BankWithdrawalRequest;
import com.travelpay.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 신규 성공 201, 신규 업무 거절 200 + FAILED, 같은 requestId 재요청 200 + 기존 결과.
 * 장애 주입(Mock-Fault)은 BankFaultInjectionFilter가 커밋 뒤에 적용한다.
 */
@RestController
@RequestMapping("/internal/v1/bank")
@RequiredArgsConstructor
public class BankTransactionController {

    private final BankWithdrawalService withdrawalService;
    private final BankRefundService refundService;
    private final BankTransactionQueryService queryService;

    @PostMapping("/withdrawals")
    public ResponseEntity<ApiResponse<BankTransactionResponse>> withdraw(
            @Valid @RequestBody BankWithdrawalRequest request) {
        return respond(withdrawalService.withdraw(request.toCommand()));
    }

    @PostMapping("/refunds")
    public ResponseEntity<ApiResponse<BankTransactionResponse>> refund(
            @Valid @RequestBody BankRefundRequest request) {
        return respond(refundService.refund(request.toCommand()));
    }

    @GetMapping("/transactions/{requestId}")
    public ApiResponse<BankTransactionResponse> find(@PathVariable String requestId) {
        return ApiResponse.success(BankTransactionResponse.from(queryService.findByRequestId(requestId)));
    }

    private static ResponseEntity<ApiResponse<BankTransactionResponse>> respond(BankTransactionResult result) {
        HttpStatus status = result.created() && result.status() == BankTransactionStatus.SUCCEEDED
                ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.success(BankTransactionResponse.from(result)));
    }
}
