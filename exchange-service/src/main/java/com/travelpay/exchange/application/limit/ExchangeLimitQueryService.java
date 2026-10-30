package com.travelpay.exchange.application.limit;

import java.util.Arrays;
import java.util.List;

import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.quote.AmountRange;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 보내는 통화별 1회 환전 한도를 조회합니다.
 * 견적 발급이 검사하는 QuotePolicy를 그대로 읽어, 안내하는 한도와 실제 검사하는 한도가 같게 합니다.
 */
@Service
@RequiredArgsConstructor
public class ExchangeLimitQueryService {

    private final QuotePolicy quotePolicy;

    /** 지원 통화 전체의 1회 한도입니다. 응답 순서가 바뀌지 않도록 통화 선언 순서(KRW, JPY, CNY)로 돌려줍니다. */
    public List<AmountRange> findAll() {
        return Arrays.stream(Currency.values())
                .map(quotePolicy.limits()::get)
                .toList();
    }
}
