package com.travelpay.exchange.application.quote;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteErrorCode;
import com.travelpay.exchange.domain.quote.ExchangeQuoteRepository;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuotePolicy;
import com.travelpay.exchange.domain.quote.QuoteStatus;
import com.travelpay.exchange.domain.quote.QuoteType;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.RateFreshnessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 저장된 최신 환율로 견적을 발급하고, 본인 견적을 조회합니다. 외부 환율 API는 호출하지 않습니다. */
@Service
@RequiredArgsConstructor
public class ExchangeQuoteService {

    private final ExchangeRateQueryService rateQueryService;
    private final RateFreshnessPolicy freshnessPolicy;
    private final QuotePolicy quotePolicy;
    private final ExchangeQuoteRepository repository;
    private final Clock clock;

    /**
     * 통화쌍과 금액 형식을 먼저 확인한 뒤, 현재 모드의 최신 환율이 견적에 쓸 수 있을 만큼 최신일 때만 발급합니다.
     * 환율이 없거나 오래됐으면 EXCHANGE_RATE_UNAVAILABLE 오류입니다.
     *
     * 트랜잭션을 걸지 않습니다. 견적은 Redis에 저장하고 환율도 캐시에서 읽으므로, 트랜잭션을 걸면 쓰지도 않을 DB 연결을
     * 요청마다 빌리게 됩니다(JPA 트랜잭션은 시작할 때 연결을 빌림). 캐시가 비어 환율을 DB에서 읽을 때만 연결을 씁니다.
     */
    public ExchangeQuoteView issue(IssueQuoteCommand command) {
        QuoteCurrencies currencies = new QuoteCurrencies(command.fromCurrency(), command.toCurrency());
        Money amount = Money.of(command.fromCurrency(), command.amount());
        Instant now = clock.instant();
        ExchangeRate rate = rateQueryService.findLatest(currencies.foreign())
                .filter(latest -> freshnessPolicy.isFresh(latest.getRateDate(), latest.getCollectedAt(), now))
                .orElseThrow(() -> new BusinessException(ExchangeQuoteErrorCode.RATE_UNAVAILABLE));
        ExchangeQuote quote = repository.save(
                ExchangeQuote.issue(command.userId(), QuoteType.USER, currencies, amount, rate, quotePolicy, now));
        return new ExchangeQuoteView(quote, QuoteStatus.ISSUED);
    }

    /**
     * 본인 견적을 조회합니다. 없거나 다른 사람의 견적이면 EXCHANGE_QUOTE_NOT_FOUND 오류입니다.
     * 견적은 만료 후 일정 시간만 남아 있다가 사라지므로, 오래된 견적도 EXCHANGE_QUOTE_NOT_FOUND입니다.
     */
    public ExchangeQuoteView find(UUID userId, UUID quoteId) {
        ExchangeQuote quote = repository.findByIdAndUserId(quoteId, userId)
                .orElseThrow(() -> new BusinessException(ExchangeQuoteErrorCode.QUOTE_NOT_FOUND));
        return new ExchangeQuoteView(quote, quote.isExpired(clock.instant()) ? QuoteStatus.EXPIRED : QuoteStatus.ISSUED);
    }
}
