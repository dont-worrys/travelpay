package com.travelpay.exchange.presentation.rate;

import java.util.List;

import com.travelpay.exchange.domain.rate.ForeignCurrency;

/**
 * 환율 조회 조건입니다. 쿼리 파라미터를 받습니다.
 *
 * @param currency 조회할 통화 코드 (대소문자 무관). 지정하지 않으면 지원 통화 전체를 조회합니다.
 */
public record ExchangeRateSearchRequest(String currency) {

    /** 조회할 통화 목록입니다. 지원하지 않는 통화면 EXCHANGE_UNSUPPORTED_CURRENCY 오류입니다. */
    public List<ForeignCurrency> targetCurrencies() {
        if (currency == null) {
            return List.of(ForeignCurrency.values());
        }
        return List.of(ForeignCurrency.fromCode(currency));
    }
}
