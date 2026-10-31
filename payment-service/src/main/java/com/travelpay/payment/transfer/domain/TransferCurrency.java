package com.travelpay.payment.transfer.domain;

import java.util.Arrays;

/**
 * 송금을 지원하는 통화입니다. MVP 기간에는 환전 담당자와 합의한 KRW·JPY·CNY만 허용합니다.
 * DB의 ck_transfer_currency는 형식만 검사하므로 통화를 추가할 때 마이그레이션은 필요하지 않습니다.
 */
public enum TransferCurrency {
    KRW,
    JPY,
    CNY;

    public static boolean isSupported(String currency) {
        return Arrays.stream(values()).anyMatch(value -> value.name().equals(currency));
    }
}
