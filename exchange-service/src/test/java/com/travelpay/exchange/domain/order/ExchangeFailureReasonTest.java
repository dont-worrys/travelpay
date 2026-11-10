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
    @EnumSource(value = ExchangeFailureReason.class, names = {
            "INSUFFICIENT_BALANCE", "WALLET_SUSPENDED", "ACCOUNT_NOT_FOUND"
    })
    void mapsKnownLedgerRejectCodeAndReason(ExchangeFailureReason reason) {
        assertEquals(Optional.of(reason),
                ExchangeFailureReason.fromLedgerRejectCode("LEDGER_REJECTED_" + reason.name()));
        assertEquals(Optional.of(reason),
                ExchangeFailureReason.fromLedgerRejectReason(reason.name()));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "LEDGER_REJECTED_DAILY_LIMIT",
            "LEDGER_REJECTED_NOT_PROCESSED",
            "LEDGER_REJECTED_PROCESSING_ERROR",
            "INSUFFICIENT_BALANCE",
            "LEDGER_REFERENCE_CONFLICT",
            ""
    })
    void unknownCodeIsEmpty(String rejectCode) {
        assertEquals(Optional.empty(), ExchangeFailureReason.fromLedgerRejectCode(rejectCode));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"DAILY_LIMIT", "NOT_PROCESSED", "PROCESSING_ERROR", "LEDGER_REJECTED_INSUFFICIENT_BALANCE", ""})
    void unknownRejectReasonIsEmpty(String rejectReason) {
        assertEquals(Optional.empty(), ExchangeFailureReason.fromLedgerRejectReason(rejectReason));
    }

    @Test
    void respondsWithUnprocessableErrorCode() {
        assertEquals("EXCHANGE_LEDGER_REJECTED_INSUFFICIENT_BALANCE",
                ExchangeFailureReason.INSUFFICIENT_BALANCE.errorCode().getCode());
        assertEquals("EXCHANGE_NOT_PROCESSED",
                ExchangeFailureReason.NOT_PROCESSED.errorCode().getCode());
        assertEquals("처리 시간이 초과되어 취소되었습니다.",
                ExchangeFailureReason.NOT_PROCESSED.errorCode().getMessage());
        assertEquals("EXCHANGE_PROCESSING_ERROR",
                ExchangeFailureReason.PROCESSING_ERROR.errorCode().getCode());
        assertEquals("처리 중 오류로 취소되었습니다.",
                ExchangeFailureReason.PROCESSING_ERROR.errorCode().getMessage());
        for (ExchangeFailureReason reason : ExchangeFailureReason.values()) {
            assertEquals(422, reason.errorCode().getStatus());
        }
    }
}
