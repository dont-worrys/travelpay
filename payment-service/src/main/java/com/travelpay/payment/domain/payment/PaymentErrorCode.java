package com.travelpay.payment.domain.payment;

import com.travelpay.common.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
    MERCHANT_NOT_FOUND("PAYMENT_MERCHANT_NOT_FOUND", "가맹점을 찾을 수 없습니다.", 404),
    MERCHANT_OWNER_NOT_FOUND("PAYMENT_MERCHANT_OWNER_NOT_FOUND", "가맹점 주인으로 지정할 회원을 찾을 수 없습니다.", 404),
    MERCHANT_NOT_ACTIVE("PAYMENT_MERCHANT_NOT_ACTIVE", "현재 결제가 불가능한 가맹점입니다.", 422),
    WALLET_NOT_FOUND("PAYMENT_WALLET_NOT_FOUND", "지갑을 찾을 수 없습니다.", 404),
    WALLET_NOT_ACTIVE("PAYMENT_WALLET_NOT_ACTIVE", "현재 결제가 불가능한 지갑입니다.", 422),
    CURRENCY_MISMATCH("PAYMENT_CURRENCY_MISMATCH", "가맹점에서 사용할 수 없는 통화입니다.", 400),
    INVALID_AMOUNT("PAYMENT_INVALID_AMOUNT", "결제 금액은 0보다 커야 합니다.", 400),
    INVALID_STATUS_TRANSITION("PAYMENT_INVALID_STATUS_TRANSITION", "현재 상태에서 처리할 수 없는 결제입니다.", 409),
    DECLINED("PAYMENT_DECLINED", "결제가 거절되었습니다.", 422),
    IDEMPOTENCY_KEY_REUSED("PAYMENT_IDEMPOTENCY_KEY_REUSED", "이미 다른 결제에 사용된 요청 키입니다.", 409),
    PAYMENT_NOT_FOUND("PAYMENT_NOT_FOUND", "결제를 찾을 수 없습니다.", 404),
    NOT_REFUNDABLE("PAYMENT_NOT_REFUNDABLE", "환불할 수 없는 결제입니다.", 409),
    REFUND_IN_PROGRESS("PAYMENT_REFUND_IN_PROGRESS", "환불이 진행 중인 결제입니다.", 409),
    ALREADY_REFUNDED("PAYMENT_ALREADY_REFUNDED", "이미 환불된 결제입니다.", 409),
    REFUND_DECLINED("PAYMENT_REFUND_DECLINED", "환불이 거절되었습니다.", 422),
    REFUND_IDEMPOTENCY_KEY_REUSED("PAYMENT_REFUND_IDEMPOTENCY_KEY_REUSED", "이미 다른 환불에 사용된 요청 키입니다.", 409),
    ;
    private final String code;
    private final String message;
    private final int status;
}
