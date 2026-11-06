package com.travelpay.wallet.application.resolution;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.common.error.ErrorResponse;

import java.util.List;

/**
 * 보정을 반영하지 않음 (판정 불가 409·조회 불가 503). 응답 details에 거절 이유와 지금 사실을 담는다.
 * 멱등 키는 소비하지 않으므로 외부 상태가 바뀐 뒤 같은 키로 다시 판단할 수 있다
 */
public class ChargeResolutionRejectedException extends BusinessException {

    private final List<ErrorResponse.FieldError> details;

    public ChargeResolutionRejectedException(ErrorCode errorCode, List<ErrorResponse.FieldError> details) {
        super(errorCode);
        this.details = List.copyOf(details);
    }

    public List<ErrorResponse.FieldError> getDetails() {
        return details;
    }
}
