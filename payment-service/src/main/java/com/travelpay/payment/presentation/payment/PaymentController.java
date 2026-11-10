package com.travelpay.payment.presentation.payment;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.RefundCommand;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.presentation.payment.dto.CustomerRefundRequest;
import com.travelpay.payment.presentation.payment.dto.PaymentListResponse;
import com.travelpay.payment.presentation.payment.dto.PaymentRequest;
import com.travelpay.payment.presentation.payment.dto.PaymentResponse;
import com.travelpay.payment.presentation.payment.dto.RefundRejectRequest;
import com.travelpay.payment.presentation.payment.dto.RefundRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "결제", description = "결제 요청·조회, 고객 환불 신청·취소, 가맹점 주인·MASTER의 환불 신청 거절·환불 실행")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "결제 요청")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201",
                    description = "결제 접수. `status`로 결과 구분: `APPROVED` 승인, `PENDING` 결과 확인 중(서버가 자동으로 다시 처리), `MANUAL_REVIEW` 운영자 확인 필요")
    })
    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> paymentRequest(@CurrentUser AuthenticatedUser user,
                                                                      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
                                                                      @RequestBody @Valid PaymentRequest request) {
        PaymentCommand command = request.toCommand(user.userId(), idempotencyKey);
        PaymentResponse response = PaymentResponse.from(paymentService.paymentRequest(command));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @Operation(summary = "내 결제 목록")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "최신순 페이지")
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PaymentListResponse>>> getPayments(@CurrentUser AuthenticatedUser user,
                                                                                     @RequestParam(required = false) PaymentStatus status,
                                                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                                                     @RequestParam(defaultValue = "20") @Min(1) int size) {
        PageResponse<PaymentListResponse> response = PageResponse.from(
                paymentService.getPayments(user.userId(), status, page, size), PaymentListResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "내 결제 상세")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "결제와 환불 내역(`refunds`), 가장 최근 고객 환불 신청 요약(`refundRequest`)")
    })
    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@CurrentUser AuthenticatedUser user,
                                                                  @PathVariable UUID paymentId) {
        PaymentResponse response = PaymentResponse.from(paymentService.getPayment(user.userId(), paymentId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "환불 실행 (가맹점 주인·MASTER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "결제 객체. 방금 만든 환불은 `refunds`에 들어 있음. 결과 확인 중이면 결제 `REFUND_PENDING`·환불 `REQUESTED`")
    })
    @PostMapping("/{paymentId}/refunds")
    public ResponseEntity<ApiResponse<PaymentResponse>> refundRequest(@CurrentUser AuthenticatedUser user,
                                                                      @PathVariable UUID paymentId,
                                                                      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
                                                                      @RequestBody @Valid RefundRequest request) {
        RefundCommand command = request.toCommand(user, paymentId, idempotencyKey);
        PaymentResponse response = PaymentResponse.from(paymentService.refundRequest(command));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 고객 환불 신청. 이미 신청한 결제면 현재 상태로 응답
    @Operation(summary = "환불 신청 (결제한 회원)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "`REFUND_REQUESTED`가 된 결제. 이미 신청한 결제면 현재 상태")
    })
    @PostMapping("/{paymentId}/refund-request")
    public ResponseEntity<ApiResponse<PaymentResponse>> requestRefund(@CurrentUser AuthenticatedUser user,
                                                                      @PathVariable UUID paymentId,
                                                                      @RequestBody @Valid CustomerRefundRequest request) {
        PaymentResponse response = PaymentResponse.from(paymentService.requestRefund(user.userId(), paymentId, request.reason()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 고객 환불 신청 취소
    @Operation(summary = "환불 신청 취소 (결제한 회원)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "`APPROVED`로 돌아온 결제")
    })
    @DeleteMapping("/{paymentId}/refund-request")
    public ResponseEntity<ApiResponse<PaymentResponse>> cancelRefundRequest(@CurrentUser AuthenticatedUser user,
                                                                            @PathVariable UUID paymentId) {
        PaymentResponse response = PaymentResponse.from(paymentService.cancelRefundRequest(user.userId(), paymentId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 가맹점 주인·MASTER의 환불 신청 거절
    @Operation(summary = "환불 신청 거절 (가맹점 주인·MASTER)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "`APPROVED`로 돌아온 결제. 거절 사유는 고객에게 `refundRequest.rejectReason`으로 보임")
    })
    @PostMapping("/{paymentId}/refund-request/reject")
    public ResponseEntity<ApiResponse<PaymentResponse>> rejectRefundRequest(@CurrentUser AuthenticatedUser user,
                                                                            @PathVariable UUID paymentId,
                                                                            @RequestBody @Valid RefundRejectRequest request) {
        PaymentResponse response = PaymentResponse.from(
                paymentService.rejectRefundRequest(user, paymentId, request.reason()));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
