package com.travelpay.ledger.domain;

/**
 * 지원 통화. 금액은 최소 단위 정수(long)로 다룬다.
 * fractionDigits: 최소 단위 자릿수 (CNY 1050 = 10.50위안)
 */
public enum Currency {
    KRW(0),
    JPY(0),
    CNY(2);

    private final int fractionDigits;

    Currency(int fractionDigits) {
        this.fractionDigits = fractionDigits;
    }

    public int fractionDigits() {
        return fractionDigits;
    }
}
