package com.travelpay.payment.presentation.payment;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.application.payment.PaymentReviewService;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.domain.payment.ReviewTarget;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import com.travelpay.payment.presentation.payment.dto.ManualReviewResolutionResponse;
import com.travelpay.payment.presentation.payment.dto.ManualReviewResolveRequest;
import com.travelpay.payment.presentation.payment.dto.ManualReviewResolveResponse;
import com.travelpay.payment.presentation.payment.dto.OverdueRefundRequestResponse;
import com.travelpay.payment.presentation.payment.dto.PaymentManualReviewResponse;
import com.travelpay.payment.presentation.payment.dto.PaymentReviewDetailResponse;
import com.travelpay.payment.presentation.payment.dto.RefundManualReviewResponse;
import com.travelpay.payment.presentation.payment.dto.RefundReviewDetailResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

// MANUAL_REVIEW 결제·환불 운영자 처리, 오래된 환불 신청 확인 API (MASTER 전용). 관리자 API는 /api/v1/admin/** 아래에 둔다
@Tag(name = "결제 운영자 처리", description = "MASTER의 MANUAL_REVIEW 결제·환불 처리, 처리 기록, 오래된 환불 신청 조회")
@RestController
@RequestMapping("/api/v1/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentReviewService paymentReviewService;
    private final PaymentService paymentService;

    // 신청 후 3일 넘게 가맹점이 처리하지 않은 환불 신청
    @Operation(summary = "3일 넘게 처리되지 않은 환불 신청")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "오래된 순 페이지")
    })
    @GetMapping("/refund-requests/overdue")
    public ResponseEntity<ApiResponse<PageResponse<OverdueRefundRequestResponse>>> getOverdueRefundRequests(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        requireMaster(user);
        PageResponse<OverdueRefundRequestResponse> response = PageResponse.from(
                paymentService.getOverdueRefundRequests(page, size), OverdueRefundRequestResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "MANUAL_REVIEW 결제 목록")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "오래된 순 페이지. `reviewReason`으로 거름")
    })
    @GetMapping("/manual-reviews")
    public ResponseEntity<ApiResponse<PageResponse<PaymentManualReviewResponse>>> getPaymentManualReviews(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) SettlementReviewReason reviewReason,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        requireMaster(user);
        PageResponse<PaymentManualReviewResponse> response = PageResponse.from(
                paymentReviewService.getPaymentManualReviews(reviewReason, page, size),
                PaymentManualReviewResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "MANUAL_REVIEW 환불 목록")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "오래된 순 페이지. `reviewReason`으로 거름")
    })
    @GetMapping("/refunds/manual-reviews")
    public ResponseEntity<ApiResponse<PageResponse<RefundManualReviewResponse>>> getRefundManualReviews(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) SettlementReviewReason reviewReason,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        requireMaster(user);
        PageResponse<RefundManualReviewResponse> response = PageResponse.from(
                paymentReviewService.getRefundManualReviews(reviewReason, page, size),
                RefundManualReviewResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    // 운영자 처리 기록 목록(최신순)
    @Operation(summary = "결제·환불 운영자 처리 기록 목록")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "최신순 페이지. `type`(`PAYMENT`·`REFUND`)·`actorId`·`paymentId`로 거름")
    })
    @GetMapping("/resolutions")
    public ResponseEntity<ApiResponse<PageResponse<ManualReviewResolutionResponse>>> getResolutions(
            @CurrentUser AuthenticatedUser user,
            @RequestParam(required = false) ReviewTarget type,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) int size) {
        requireMaster(user);
        PageResponse<ManualReviewResolutionResponse> response = PageResponse.from(
                paymentReviewService.getResolutions(type, actorId, paymentId, page, size),
                ManualReviewResolutionResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "MANUAL_REVIEW 결제 단건 (원장 조회·예상 결과)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "원장 조회 결과(`ledger`)와 처리하면 될 결과(`expected`). 원장 조회 실패면 `ledgerLookupFailed: true`")
    })
    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentReviewDetailResponse>> getPaymentReview(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID paymentId) {
        requireMaster(user);
        PaymentReviewDetailResponse response = PaymentReviewDetailResponse.from(
                paymentReviewService.getPaymentReview(user.userId(), paymentId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "MANUAL_REVIEW 환불 단건 (원장 조회·예상 결과)")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "원장 조회 결과(`ledger`)와 처리하면 될 결과(`expected`). 원장 조회 실패면 `ledgerLookupFailed: true`")
    })
    @GetMapping("/refunds/{refundId}")
    public ResponseEntity<ApiResponse<RefundReviewDetailResponse>> getRefundReview(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID refundId) {
        requireMaster(user);
        RefundReviewDetailResponse response = RefundReviewDetailResponse.from(
                paymentReviewService.getRefundReview(user.userId(), refundId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "MANUAL_REVIEW 결제 처리")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "처리 결과(`status`, `paymentStatus`, `ledgerStatus`, `ledgerTransactionId`). 결과는 원장 조회로 서버가 정함")
    })
    @PostMapping("/{paymentId}/resolve")
    public ResponseEntity<ApiResponse<ManualReviewResolveResponse>> resolvePayment(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID paymentId,
            @RequestBody(required = false) @Valid ManualReviewResolveRequest request) {
        requireMaster(user);
        ManualReviewResolveResponse response = ManualReviewResolveResponse.from(
                paymentReviewService.resolvePayment(user.userId(), paymentId, ManualReviewResolveRequest.memoOf(request)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "MANUAL_REVIEW 환불 처리")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
                    description = "처리 결과(`status`, `paymentStatus`, `ledgerStatus`, `ledgerTransactionId`). 결과는 원장 조회로 서버가 정함")
    })
    @PostMapping("/refunds/{refundId}/resolve")
    public ResponseEntity<ApiResponse<ManualReviewResolveResponse>> resolveRefund(
            @CurrentUser AuthenticatedUser user,
            @PathVariable UUID refundId,
            @RequestBody(required = false) @Valid ManualReviewResolveRequest request) {
        requireMaster(user);
        ManualReviewResolveResponse response = ManualReviewResolveResponse.from(
                paymentReviewService.resolveRefund(user.userId(), refundId, ManualReviewResolveRequest.memoOf(request)));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    private static void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }
}
