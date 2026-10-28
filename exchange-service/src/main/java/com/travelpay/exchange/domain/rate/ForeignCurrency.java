package com.travelpay.exchange.domain.rate;

import java.util.Locale;

import com.travelpay.common.error.BusinessException;

/** 원화(KRW) 대비 환율을 제공하는 외화입니다. */
public enum ForeignCurrency {
    JPY(100),
    CNY(1);

    private final int quoteUnit;

    ForeignCurrency(int quoteUnit) {
        this.quoteUnit = quoteUnit;
    }

    /**
     * 환율을 표시하는 외화 단위입니다. 은행 관례대로 엔화는 100엔당, 위안화는 1위안당 가격입니다.
     * 예약 목표환율과 현재 환율을 같은 기준으로 비교하도록 통화마다 고정합니다.
     */
    public int quoteUnit() {
        return quoteUnit;
    }

    /** 통화 코드를 대소문자 구분 없이 해석합니다. 지원하지 않는 통화는 업무 오류로 거절합니다. */
    public static ForeignCurrency fromCode(String code) {
        if (code != null) {
            String normalized = code.trim().toUpperCase(Locale.ROOT);
            for (ForeignCurrency currency : values()) {
                if (currency.name().equals(normalized)) {
                    return currency;
                }
            }
        }
        throw new BusinessException(ExchangeRateErrorCode.UNSUPPORTED_CURRENCY);
    }
}
