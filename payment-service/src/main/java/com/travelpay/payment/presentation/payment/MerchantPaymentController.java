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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

// 가맹점이 자기 결제를 조회하는 API. 경로는 가맹점 아래지만 결제 데이터라 결제 쪽에서 처리
@Tag(name = "가맹점", description = "MASTER의 가맹점 생성·원장 계정 재시도, 가맹점 주인·MASTER의 잔액·받은 결제 조회")
@RestController
@RequestMapping("/api/v1/payments/merchants/{merchantId}/payments")
@RequiredArgsConstructor
public class MerchantPaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "가맹점 결제 목록 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "최신순 페이지. `refundedAmount`는 완료된 환불 합계. 처리할 환불 신청은 `status=REFUND_REQUESTED`로 거름")
    })
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

    @Operation(summary = "가맹점 결제 상세 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "결제 객체(`refunds`, `refundRequest` 포함)")
    })
    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@CurrentUser AuthenticatedUser user,
                                                                  @PathVariable UUID merchantId,
                                                                  @PathVariable UUID paymentId) {
        PaymentResponse response = PaymentResponse.from(paymentService.getMerchantPayment(user, merchantId, paymentId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
