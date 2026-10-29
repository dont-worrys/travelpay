package com.travelpay.exchange.application.quote;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.exchange.application.rate.ExchangeRateQueryService;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import com.travelpay.exchange.domain.money.MoneyErrorCode;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.ExchangeQuoteErrorCode;
import com.travelpay.exchange.domain.quote.ExchangeQuoteRepository;
import com.travelpay.exchange.domain.quote.QuoteCurrencies;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.quote.QuoteType;
import com.travelpay.exchange.domain.rate.ExchangeRate;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import com.travelpay.exchange.domain.rate.RateFreshnessPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ExchangeQuoteServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final ExchangeRate JPY = QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.3");

    private final ExchangeRateQueryService rateQueryService = mock(ExchangeRateQueryService.class);
    private final RateFreshnessPolicy freshnessPolicy = mock(RateFreshnessPolicy.class);
    private final ExchangeQuoteRepository repository = mock(ExchangeQuoteRepository.class);
    private final ExchangeQuoteService service = new ExchangeQuoteService(rateQueryService, freshnessPolicy,
            QuoteFixtures.policy(), repository, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

    @Test
    void issuesQuoteWithLatestFreshRateAndSavesIt() {
        when(rateQueryService.findLatest(ForeignCurrency.JPY)).thenReturn(Optional.of(JPY));
        when(freshnessPolicy.isFresh(JPY.getRateDate(), JPY.getCollectedAt(), NOW)).thenReturn(true);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ExchangeQuoteView view = service.issue(command(Currency.KRW, Currency.JPY, "100000"));

        assertEquals(new Money(Currency.JPY, 11928), view.quote().toMoney());
        assertEquals(USER_ID, view.quote().getUserId());
        assertEquals(QuoteType.USER, view.quote().getQuoteType());
        assertEquals(NOW.plusSeconds(300), view.quote().getExpiresAt());
        assertFalse(view.expired());
        verify(repository).save(view.quote());
    }

    @Test
    void rejectsWhenNoRateIsStored() {
        when(rateQueryService.findLatest(ForeignCurrency.JPY)).thenReturn(Optional.empty());

        assertError(ExchangeQuoteErrorCode.RATE_UNAVAILABLE,
                () -> service.issue(command(Currency.KRW, Currency.JPY, "100000")));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsWhenLatestRateIsStale() {
        when(rateQueryService.findLatest(ForeignCurrency.JPY)).thenReturn(Optional.of(JPY));
        when(freshnessPolicy.isFresh(any(), any(), any())).thenReturn(false);

        assertError(ExchangeQuoteErrorCode.RATE_UNAVAILABLE,
                () -> service.issue(command(Currency.KRW, Currency.JPY, "100000")));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsUnsupportedPairBeforeLookingUpRate() {
        assertError(ExchangeQuoteErrorCode.UNSUPPORTED_PAIR,
                () -> service.issue(command(Currency.JPY, Currency.CNY, "1000")));
        verifyNoInteractions(rateQueryService);
    }

    @Test
    void rejectsAmountWithTooManyDigitsBeforeLookingUpRate() {
        assertError(MoneyErrorCode.INVALID_AMOUNT,
                () -> service.issue(command(Currency.JPY, Currency.KRW, "100.5")));
        verifyNoInteractions(rateQueryService);
    }

    @Test
    void findsOwnQuoteAndReportsExpiry() {
        ExchangeQuote expired = ExchangeQuote.issue(USER_ID, QuoteType.USER,
                new QuoteCurrencies(Currency.KRW, Currency.JPY), new Money(Currency.KRW, 100000),
                JPY, QuoteFixtures.policy(), NOW.minusSeconds(300));
        UUID quoteId = UUID.randomUUID();
        when(repository.findByIdAndUserId(quoteId, USER_ID)).thenReturn(Optional.of(expired));

        ExchangeQuoteView view = service.find(USER_ID, quoteId);

        assertSame(expired, view.quote());
        assertTrue(view.expired());
    }

    @Test
    void rejectsQuoteThatIsMissingOrOwnedByOthers() {
        UUID quoteId = UUID.randomUUID();
        when(repository.findByIdAndUserId(quoteId, USER_ID)).thenReturn(Optional.empty());

        assertError(ExchangeQuoteErrorCode.QUOTE_NOT_FOUND, () -> service.find(USER_ID, quoteId));
    }

    private static IssueQuoteCommand command(Currency from, Currency to, String amount) {
        return new IssueQuoteCommand(USER_ID, from, to, new BigDecimal(amount));
    }

    private static void assertError(Object errorCode, Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        assertEquals(errorCode, exception.getErrorCode());
    }
}
