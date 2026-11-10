package com.travelpay.payment.domain.payment;

import com.travelpay.common.error.BusinessException;
import lombok.Getter;

// 원장이 결제·환불을 거절했을 때. 에러 코드(DECLINED / REFUND_DECLINED)와 함께 거절 사유를 담는다
@Getter
public class PaymentDeclinedException extends BusinessException {

    private final String declineReason;

    public PaymentDeclinedException(PaymentErrorCode errorCode, String declineReason) {
        super(errorCode);
        this.declineReason = declineReason;
    }
}
