package com.travelpay.ledger.application.transaction.dto;

import com.travelpay.ledger.domain.transaction.ReferenceType;

import java.time.Instant;

/** 기간 목록 조회 조건. cursor·size는 없을 수 있다 (size 없으면 기본값) */
public record TransactionPeriodQuery(ReferenceType referenceType, Instant from, Instant to, String cursor,
                                     Integer size) {
}
