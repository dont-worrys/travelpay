package com.travelpay.ledger.presentation.transaction.dto;

import com.travelpay.ledger.application.transaction.dto.TransactionPeriodResult;

import java.util.List;

/** 기간 목록 한 페이지. items는 거래 조회와 같은 형식, 다음 페이지가 없으면 nextCursor는 null */
public record TransactionPeriodResponse(List<TransactionResultResponse> items, String nextCursor) {

    public static TransactionPeriodResponse from(TransactionPeriodResult result) {
        return new TransactionPeriodResponse(
                result.items().stream().map(TransactionResultResponse::from).toList(), result.nextCursor());
    }
}
