package com.travelpay.payment.application.payment.client.ledger;

import java.util.UUID;

public interface PaymentLedgerClient {
    PaymentLedgerResult createTransaction(PaymentLedgerTransactionCommand command);
    PaymentLedgerResult refundTransaction(PaymentLedgerRefundCommand command);
    PaymentLedgerResult settlementTransaction(PaymentLedgerSettlementCommand command);

    /** 원장을 조회하지 못하면 LEDGER_LOOKUP_UNAVAILABLE(503) */
    PaymentLedgerLookup findPaymentTransaction(UUID paymentId);
    PaymentLedgerLookup findRefundTransaction(UUID refundId);
    PaymentLedgerLookup findSettlementTransaction(UUID settlementId);

    /**
     * 원장에 reference를 취소 기록으로 남겨, 늦게 도착한 등록이 반영되지 않게 합니다(운영자 처리 전용).
     * 취소로 막았으면 NOT_FOUND(원장에 반영된 거래 없음 확정), 이미 거래가 있으면 그 거래(POSTED·REJECTED)를 돌려줍니다.
     * 원장 응답을 받지 못하면 LEDGER_LOOKUP_UNAVAILABLE(503)
     */
    PaymentLedgerLookup cancelPaymentTransaction(UUID paymentId, UUID actorId);
    PaymentLedgerLookup cancelSettlementTransaction(UUID settlementId, UUID actorId);
}
