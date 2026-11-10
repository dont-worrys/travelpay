package com.travelpay.wallet.presentation.charge;

import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.resolution.ChargeResolutionRejectedException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 보정 거절(판정 불가 409·조회 불가 503)은 거절 이유와 지금 사실을 error.details에 담는다.
 * common 처리기는 BusinessException의 details를 싣지 않으므로 먼저 잡는다
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class ChargeResolutionExceptionHandler {

    @ExceptionHandler(ChargeResolutionRejectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleRejected(ChargeResolutionRejectedException exception) {
        var errorCode = exception.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.failure(ErrorResponse.of(errorCode, exception.getDetails())));
    }
}
