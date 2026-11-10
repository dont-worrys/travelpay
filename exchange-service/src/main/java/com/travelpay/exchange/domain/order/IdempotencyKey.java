package com.travelpay.exchange.domain.order;

import java.util.regex.Pattern;

import com.travelpay.common.error.BusinessException;

/**
 * 환전 신청 요청의 멱등 키입니다. 같은 회원이 같은 키로 다시 보내면 같은 신청으로 봅니다.
 * 공백·제어 문자를 뺀 ASCII 1~100자이며 exchange_orders.idempotency_key 길이와 같습니다.
 * UUID도 이 형식에 들어갑니다.
 */
public record IdempotencyKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[\\x21-\\x7E]{1,100}$");

    /** 형식이 아니면 EXCHANGE_INVALID_IDEMPOTENCY_KEY(400) 오류입니다. 앞뒤 공백도 허용하지 않습니다. */
    public IdempotencyKey {
        if (value == null || !FORMAT.matcher(value).matches()) {
            throw new BusinessException(ExchangeOrderErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
    }
}
