package com.travelpay.payment.presentation.payment;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.application.payment.dto.RefundCommand;
import com.travelpay.payment.domain.payment.PaymentStatus;
import com.travelpay.payment.presentation.payment.dto.PaymentListResponse;
import com.travelpay.payment.presentation.payment.dto.PaymentRequest;
import com.travelpay.payment.presentation.payment.dto.PaymentResponse;
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

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    public ResponseEntity<ApiResponse<PaymentResponse>> paymentRequest(@CurrentUser AuthenticatedUser user,
                                                                      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
                                                                      @RequestBody @Valid PaymentRequest request) {
        PaymentCommand command = request.toCommand(user.userId(), idempotencyKey);
        PaymentResponse response = PaymentResponse.from(paymentService.paymentRequest(command));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PaymentListResponse>>> getPayments(@CurrentUser AuthenticatedUser user,
                                                                                     @RequestParam(required = false) PaymentStatus status,
                                                                                     @RequestParam(defaultValue = "0") @Min(0) int page,
                                                                                     @RequestParam(defaultValue = "20") @Min(1) int size) {
        PageResponse<PaymentListResponse> response = PageResponse.from(
                paymentService.getPayments(user.userId(), status, page, size), PaymentListResponse::from);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@CurrentUser AuthenticatedUser user,
                                                                  @PathVariable UUID paymentId) {
        PaymentResponse response = PaymentResponse.from(paymentService.getPayment(user.userId(), paymentId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @PostMapping("/{paymentId}/refunds")
    public ResponseEntity<ApiResponse<PaymentResponse>> refundRequest(@CurrentUser AuthenticatedUser user,
                                                                      @PathVariable UUID paymentId,
                                                                      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
                                                                      @RequestBody @Valid RefundRequest request) {
        RefundCommand command = request.toCommand(user.userId(), paymentId, idempotencyKey);
        PaymentResponse response = PaymentResponse.from(paymentService.refundRequest(command));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
