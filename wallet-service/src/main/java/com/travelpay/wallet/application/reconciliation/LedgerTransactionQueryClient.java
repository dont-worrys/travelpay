package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 대사용 원장 충전 거래 조회 (referenceType = CHARGE) */
public interface LedgerTransactionQueryClient {

    /**
     * [from, to)의 충전 거래를 커서 끝까지 모두 받는다.
     * 한 페이지라도 받지 못하면 ReconciliationSourceException
     */
    List<LedgerTransactionRecord> listChargeTransactions(Instant from, Instant to);

    /** referenceId = chargeId 단건. 404 LEDGER_TRANSACTION_NOT_FOUND만 NotFound, 그 밖의 실패는 Unavailable */
    SourceLookup<LedgerTransactionRecord> findChargeTransaction(UUID chargeId);
}
