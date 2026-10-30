package com.travelpay.bank.application.transaction.dto;

import com.travelpay.bank.domain.transaction.BankFailureCode;
import com.travelpay.bank.domain.transaction.BankTransaction;
import com.travelpay.bank.domain.transaction.BankTransactionStatus;
import com.travelpay.bank.domain.transaction.BankTransactionType;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** created: 이번 요청에서 새로 기록했으면 true, 같은 requestId의 기존 기록이면 false */
public record BankTransactionResult(
        UUID bankTxId,
        String requestId,
        BankTransactionType type,
        BankTransactionStatus status,
        BankFailureCode failureCode,
        long amount,
        UUID originalTxId,
        Instant completedAt,
        boolean created
) {

    public static BankTransactionResult created(BankTransaction transaction) {
        return of(transaction, true);
    }

    public static BankTransactionResult replayed(BankTransaction transaction) {
        return of(transaction, false);
    }

    private static BankTransactionResult of(BankTransaction transaction, boolean created) {
        return new BankTransactionResult(
                transaction.getId(),
                transaction.getRequestId(),
                transaction.getType(),
                transaction.getStatus(),
                transaction.getFailureCode(),
                transaction.getAmount(),
                transaction.getOriginalTxId(),
                toStoredPrecision(transaction.getCompletedAt()),
                created
        );
    }

    /** DB(timestamptz)는 마이크로초로 반올림해 저장하므로 신규 응답도 같게 맞춰 재조회 응답과 일치시킨다. */
    private static Instant toStoredPrecision(Instant instant) {
        return instant == null ? null : instant.plusNanos(500).truncatedTo(ChronoUnit.MICROS);
    }
}
