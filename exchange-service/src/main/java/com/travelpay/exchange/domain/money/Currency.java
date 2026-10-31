package com.travelpay.exchange.domain.money;

import java.util.Locale;
import java.util.Optional;

/** 환전에서 주고받는 통화입니다. 금액은 통화별 최소 단위 정수로 다룹니다. (ISO 4217 소수 자릿수) */
public enum Currency {
    KRW(0),
    JPY(0),
    CNY(2);

    private final int fractionDigits;

    Currency(int fractionDigits) {
        this.fractionDigits = fractionDigits;
    }

    /** 최소 단위의 소수 자릿수입니다. CNY는 2자리라 10.50위안을 1050으로 저장합니다. */
    public int fractionDigits() {
        return fractionDigits;
    }

    /** 통화 코드를 대소문자 구분 없이 해석합니다. 지원하지 않는 통화면 empty입니다. */
    public static Optional<Currency> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        for (Currency currency : values()) {
            if (currency.name().equals(normalized)) {
                return Optional.of(currency);
            }
        }
        return Optional.empty();
    }
}
