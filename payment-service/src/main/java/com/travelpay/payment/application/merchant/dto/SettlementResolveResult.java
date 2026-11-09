package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.merchant.SettlementStatus;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;

import java.util.UUID;

/** MANUAL_REVIEW 정산 처리 결과. ledgerTransactionId는 원장에 기록이 없으면(NOT_FOUND) 없습니다. */
public record SettlementResolveResult(UUID settlementId, SettlementStatus status, String failureReason,
                                      ReviewLedgerStatus ledgerStatus, UUID ledgerTransactionId) {
}
