package com.travelpay.payment.presentation;

import com.travelpay.common.error.ErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.domain.payment.PaymentDeclinedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

// 결제·환불 거절 사유를 오류 응답의 details에 담는다.
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class PaymentExceptionHandler {

    private static final String DECLINE_REASON_FIELD = "declineReason";

    @ExceptionHandler(PaymentDeclinedException.class)
    public ResponseEntity<ApiResponse<Void>> handleDeclined(PaymentDeclinedException exception) {
        ErrorCode errorCode = exception.getErrorCode();
        List<ErrorResponse.FieldError> details = exception.getDeclineReason() == null
                ? List.of()
                : List.of(new ErrorResponse.FieldError(DECLINE_REASON_FIELD, exception.getDeclineReason()));
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.failure(ErrorResponse.of(errorCode, details)));
    }
}
