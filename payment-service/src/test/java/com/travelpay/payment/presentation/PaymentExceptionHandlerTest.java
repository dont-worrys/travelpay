package com.travelpay.payment.presentation;

import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.domain.payment.PaymentDeclinedException;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentExceptionHandlerTest {

    private final PaymentExceptionHandler handler = new PaymentExceptionHandler();

    @Test
    void putsPaymentDeclineReasonInDetails() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDeclined(
                new PaymentDeclinedException(PaymentErrorCode.DECLINED, "잔액이 부족합니다."));

        assertEquals(422, response.getStatusCode().value());
        ErrorResponse error = response.getBody().error();
        assertFalse(response.getBody().success());
        assertEquals("PAYMENT_DECLINED", error.code());
        assertEquals("결제가 거절되었습니다.", error.message());
        assertEquals(List.of(new ErrorResponse.FieldError("declineReason", "잔액이 부족합니다.")), error.details());
    }

    @Test
    void putsRefundDeclineReasonInDetails() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDeclined(
                new PaymentDeclinedException(PaymentErrorCode.REFUND_DECLINED, "환불할 수 없는 계정입니다."));

        assertEquals(422, response.getStatusCode().value());
        ErrorResponse error = response.getBody().error();
        assertEquals("PAYMENT_REFUND_DECLINED", error.code());
        assertEquals(List.of(new ErrorResponse.FieldError("declineReason", "환불할 수 없는 계정입니다.")), error.details());
    }

    @Test
    void leavesDetailsEmptyWhenReasonIsMissing() {
        ResponseEntity<ApiResponse<Void>> response = handler.handleDeclined(
                new PaymentDeclinedException(PaymentErrorCode.DECLINED, null));

        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().error().details().isEmpty());
    }
}
