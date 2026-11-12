package com.travelpay.ledger.application.transaction.dto;

/** created: 이번 요청으로 취소 기록을 새로 남겼는지 (false면 이미 있던 거래를 바꾸지 않고 그대로 돌려줌) */
public record CancelTransactionResult(TransactionDetailResult transaction, boolean created) {
}
