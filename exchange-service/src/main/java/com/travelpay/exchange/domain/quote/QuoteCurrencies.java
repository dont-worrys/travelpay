package com.travelpay.exchange.domain.quote;

import java.util.Objects;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.rate.ForeignCurrency;

/**
 * 견적의 보내는 통화와 받는 통화입니다. 한쪽은 원화, 다른 한쪽은 환율을 제공하는 외화여야 합니다.
 * 같은 통화끼리이거나 외화끼리면 EXCHANGE_UNSUPPORTED_PAIR 오류입니다.
 */
public record QuoteCurrencies(Currency from, Currency to) {

    /** 정확히 한쪽만 원화여야 합니다. 원화끼리나 외화끼리면 EXCHANGE_UNSUPPORTED_PAIR 오류입니다. */
    public QuoteCurrencies {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if ((from == Currency.KRW) == (to == Currency.KRW)) {
            throw new BusinessException(ExchangeQuoteErrorCode.UNSUPPORTED_PAIR);
        }
    }

    /** 원화가 아닌 쪽 통화입니다. 이 외화의 환율로 계산합니다. */
    public ForeignCurrency foreign() {
        Currency foreign = from == Currency.KRW ? to : from;
        return ForeignCurrency.of(foreign)
                .orElseThrow(() -> new BusinessException(ExchangeQuoteErrorCode.UNSUPPORTED_PAIR));
    }
}
