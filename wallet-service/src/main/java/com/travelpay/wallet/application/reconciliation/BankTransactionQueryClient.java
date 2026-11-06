package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;

import java.time.Instant;
import java.util.List;

/** 대사용 은행 거래 조회 (Mock 은행 GET /internal/v1/bank/transactions) */
public interface BankTransactionQueryClient {

    /**
     * created_at이 [from, to)인 은행 거래를 마지막 페이지까지 모두 받는다.
     * 한 페이지라도 받지 못하면 ReconciliationSourceException
     */
    List<BankTransactionRecord> listTransactions(Instant from, Instant to);

    /** requestId 단건. 404 BANK_TRANSACTION_NOT_FOUND만 NotFound, 그 밖의 실패는 Unavailable */
    SourceLookup<BankTransactionRecord> findTransaction(String requestId);
}
