package com.travelpay.payment.presentation.payment;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.presentation.payment.dto.MerchantPaymentListResponse;
import com.travelpay.payment.presentation.payment.dto.PaymentResponse;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 가맹점이 자기 결제를 조회하는 API. 경로는 가맹점 아래지만 결제 데이터라 결제 쪽에서 처리
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/payments")
@RequiredArgsConstructor
public class MerchantPaymentController {

    private final PaymentService paymentService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<MerchantPaymentListResponse>>> getPayments(@CurrentUser AuthenticatedUser user,
                                                                                             @PathVariable UUID merchantId,
                                                                                             @RequestParam(required = false) PaymentStatus status,
                                                                                             @RequestParam(defaultValue = "0") @Min(0) int page,
                                                                                             @RequestParam(defaultValue = "20") @Min(1) int size) {
        PageResponse<MerchantPaymentListResponse> response = PageResponse.from(
                paymentService.getMerchantPayments(user, merchantId, status, page, size),
                MerchantPaymentListResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@CurrentUser AuthenticatedUser user,
                                                                  @PathVariable UUID merchantId,
                                                                  @PathVariable UUID paymentId) {
        PaymentResponse response = PaymentResponse.from(paymentService.getMerchantPayment(user, merchantId, paymentId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
