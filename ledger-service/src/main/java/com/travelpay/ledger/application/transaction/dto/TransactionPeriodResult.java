package com.travelpay.ledger.application.transaction.dto;

import java.util.List;

/** 기간 목록 한 페이지. 다음 페이지가 없으면 nextCursor는 null */
public record TransactionPeriodResult(List<TransactionDetailResult> items, String nextCursor) {

    public TransactionPeriodResult {
        items = List.copyOf(items);
    }
}
