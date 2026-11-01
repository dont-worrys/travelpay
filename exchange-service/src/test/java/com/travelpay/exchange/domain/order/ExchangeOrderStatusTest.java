package com.travelpay.exchange.domain.order;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExchangeOrderStatusTest {

    @Test
    void onlyCompletedAndFailedAreSettled() {
        assertTrue(ExchangeOrderStatus.COMPLETED.isSettled());
        assertTrue(ExchangeOrderStatus.FAILED.isSettled());
        assertFalse(ExchangeOrderStatus.PROCESSING.isSettled());
        assertFalse(ExchangeOrderStatus.MANUAL_REVIEW.isSettled());
    }
}
