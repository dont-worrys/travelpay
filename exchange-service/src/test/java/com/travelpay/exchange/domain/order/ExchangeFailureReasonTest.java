package com.travelpay.exchange.domain.order;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExchangeFailureReasonTest {

    @ParameterizedTest
    @EnumSource(ExchangeFailureReason.class)
    void mapsLedgerRejectCodeOfSameName(ExchangeFailureReason reason) {
        assertEquals(Optional.of(reason), ExchangeFailureReason.fromLedgerRejectCode("LEDGER_REJECTED_" + reason.name()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"LEDGER_REJECTED_DAILY_LIMIT", "INSUFFICIENT_BALANCE", "LEDGER_REFERENCE_CONFLICT", ""})
    void unknownCodeIsEmpty(String rejectCode) {
        assertEquals(Optional.empty(), ExchangeFailureReason.fromLedgerRejectCode(rejectCode));
    }

    @Test
    void respondsWithUnprocessableErrorCode() {
        assertEquals("EXCHANGE_LEDGER_REJECTED_INSUFFICIENT_BALANCE",
                ExchangeFailureReason.INSUFFICIENT_BALANCE.errorCode().getCode());
        for (ExchangeFailureReason reason : ExchangeFailureReason.values()) {
            assertEquals(422, reason.errorCode().getStatus());
        }
    }
}
