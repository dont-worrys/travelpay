package com.travelpay.wallet.infra.client.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** 원장 기간 목록 한 페이지. 다음 페이지가 없으면 nextCursor는 null */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LedgerTransactionPeriodResponse(List<LedgerTransactionDetailResponse> items, String nextCursor) {
}
