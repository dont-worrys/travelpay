package com.travelpay.exchange.infrastructure.persistence.order;

import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import com.travelpay.exchange.domain.order.DuplicateExchangeOrderException;
import com.travelpay.exchange.domain.order.ExchangeOrder;
import com.travelpay.exchange.domain.order.ExchangeOrderStatus;
import com.travelpay.exchange.domain.order.ManualReviewResolution;
import com.travelpay.exchange.domain.order.ManualReviewResolutionReason;
import com.travelpay.exchange.domain.order.OrderFixtures;
import com.travelpay.exchange.domain.quote.ExchangeQuote;
import com.travelpay.exchange.domain.quote.QuoteFixtures;
import com.travelpay.exchange.domain.rate.ForeignCurrency;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExchangeOrderRepositoryAdapterTest {

    private final ExchangeOrderJpaRepository jpaRepository = mock(ExchangeOrderJpaRepository.class);
    private final ExchangeOrderRepositoryAdapter adapter = new ExchangeOrderRepositoryAdapter(jpaRepository);
    private final ExchangeOrder order = order();

    @ParameterizedTest
    @ValueSource(strings = {ExchangeOrderRepositoryAdapter.QUOTE_UNIQUE,
            ExchangeOrderRepositoryAdapter.IDEMPOTENCY_UNIQUE})
    void translatesOrderUniqueViolationToDuplicateException(String constraintName) {
        when(jpaRepository.saveAndFlush(order)).thenThrow(violationOf(constraintName));

        assertThrows(DuplicateExchangeOrderException.class, () -> adapter.save(order));
    }

    @Test
    void rethrowsViolationOfOtherConstraint() {
        DataIntegrityViolationException violation = violationOf("ck_exchange_orders_total_debit");
        when(jpaRepository.saveAndFlush(order)).thenThrow(violation);

        assertSame(violation, assertThrows(DataIntegrityViolationException.class, () -> adapter.save(order)));
    }

    @Test
    void rethrowsIntegrityFailureWithoutConstraintName() {
        DataIntegrityViolationException overflow = new DataIntegrityViolationException("numeric field overflow");
        when(jpaRepository.saveAndFlush(order)).thenThrow(overflow);

        assertSame(overflow, assertThrows(DataIntegrityViolationException.class, () -> adapter.save(order)));
    }

    @Test
    void resolveIfManualReviewReturnsTrueWhenOneRowUpdated() {
        UUID orderId = UUID.randomUUID();
        UUID ledgerTxId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-01T04:00:00Z");
        ManualReviewResolution resolution = new ManualReviewResolution(
                ExchangeOrderStatus.COMPLETED,
                ledgerTxId,
                ledgerTxId,
                null,
                ManualReviewResolutionReason.LEDGER_POSTED,
                now,
                UUID.randomUUID(),
                null
        );

        when(jpaRepository.resolveIfManualReview(orderId, ExchangeOrderStatus.COMPLETED, ledgerTxId, null, now, now))
                .thenReturn(1);

        assertTrue(adapter.resolveIfManualReview(orderId, resolution, now));
    }

    @Test
    void resolveIfManualReviewReturnsFalseWhenZeroRowsUpdated() {
        UUID orderId = UUID.randomUUID();
        UUID ledgerTxId = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-01T04:00:00Z");
        ManualReviewResolution resolution = new ManualReviewResolution(
                ExchangeOrderStatus.COMPLETED,
                ledgerTxId,
                ledgerTxId,
                null,
                ManualReviewResolutionReason.LEDGER_POSTED,
                now,
                UUID.randomUUID(),
                null
        );

        when(jpaRepository.resolveIfManualReview(orderId, ExchangeOrderStatus.COMPLETED, ledgerTxId, null, now, now))
                .thenReturn(0);

        assertFalse(adapter.resolveIfManualReview(orderId, resolution, now));
    }

    private static ExchangeOrder order() {
        Instant issuedAt = Instant.parse("2026-10-29T03:00:00Z");
        ExchangeQuote quote = OrderFixtures.wonToYenQuote(UUID.randomUUID(),
                QuoteFixtures.savedRate(ForeignCurrency.JPY, "838.30"), issuedAt);
        return OrderFixtures.placed(quote, UUID.randomUUID(), "key-1");
    }

    private static DataIntegrityViolationException violationOf(String constraintName) {
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("violation", new SQLException("violation", "23505"), constraintName));
    }
}
