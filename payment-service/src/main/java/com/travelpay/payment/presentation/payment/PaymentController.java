package com.travelpay.payment.presentation.payment;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.dto.PaymentCommand;
import com.travelpay.payment.presentation.payment.dto.PaymentRequest;
import com.travelpay.payment.presentation.payment.dto.PaymentResponse;
import jakarta.validation.Valid;
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
                                                                      @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                                      @RequestBody @Valid PaymentRequest request) {
        PaymentCommand command = request.toCommand(user.userId(), idempotencyKey);
        PaymentResponse response = PaymentResponse.from(paymentService.paymentRequest(command));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<ApiResponse<PaymentResponse>> getPayment(@CurrentUser AuthenticatedUser user,
                                                                  @PathVariable UUID paymentId) {
        PaymentResponse response = PaymentResponse.from(paymentService.getPayment(user.userId(), paymentId));
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
