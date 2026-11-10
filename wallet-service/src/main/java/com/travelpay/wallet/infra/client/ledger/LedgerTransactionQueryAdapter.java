package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.reconciliation.LedgerTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.ReconciliationSourceException;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 대사용 원장 충전 거래 조회 (referenceType = CHARGE). 응답 내용은 실패 사유·로그에 싣지 않는다 (예외 클래스명·상태 코드만).
 * - 목록: nextCursor가 null일 때까지. 어느 페이지든 실패·응답 이상·커서 반복·페이지 상한 초과면 ReconciliationSourceException
 * - 단건: 404 + LEDGER_TRANSACTION_NOT_FOUND만 NotFound. 그 밖의 실패는 Unavailable
 */
@Component
@RequiredArgsConstructor
public class LedgerTransactionQueryAdapter implements LedgerTransactionQueryClient {

    static final String CHARGE = "CHARGE";
    static final int PAGE_SIZE = 100;
    /** 무한 반복 방지. 100건 × 10,000페이지 = 100만 건 */
    static final int MAX_PAGES = 10_000;
    private static final int NOT_FOUND = 404;
    private static final String NOT_FOUND_CODE = "LEDGER_TRANSACTION_NOT_FOUND";

    private final LedgerTransactionFeignClient feignClient;

    @Override
    public List<LedgerTransactionRecord> listChargeTransactions(Instant from, Instant to) {
        List<LedgerTransactionRecord> records = new ArrayList<>();
        Set<String> seenCursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; ; page++) {
            if (page >= MAX_PAGES) {
                throw new ReconciliationSourceException("원장 거래 목록 페이지 상한 초과: " + MAX_PAGES);
            }
            LedgerTransactionPeriodResponse data = fetchPage(from, to, cursor, page);
            for (LedgerTransactionDetailResponse item : data.items()) {
                LedgerTransactionRecord record = toRecord(item);
                if (record == null) {
                    throw new ReconciliationSourceException("원장 거래 목록 응답 이상: 항목 필드 누락 page=" + page);
                }
                records.add(record);
            }
            cursor = data.nextCursor();
            if (cursor == null) {
                return records;
            }
            if (!seenCursors.add(cursor)) {
                throw new ReconciliationSourceException("원장 거래 목록 응답 이상: 커서 반복 page=" + page);
            }
        }
    }

    private LedgerTransactionPeriodResponse fetchPage(Instant from, Instant to, String cursor, int page) {
        ApiResponse<LedgerTransactionPeriodResponse> response;
        try {
            response = feignClient.listByPeriod(CHARGE, from, to, cursor, PAGE_SIZE);
        } catch (FeignException e) {
            throw new ReconciliationSourceException("원장 거래 목록 조회 실패 page=" + page + ": " + describe(e), e);
        }
        if (response == null || !response.success() || response.data() == null || response.data().items() == null) {
            throw new ReconciliationSourceException("원장 거래 목록 응답 이상: 본문 page=" + page);
        }
        return response.data();
    }

    @Override
    public SourceLookup<LedgerTransactionRecord> findChargeTransaction(UUID chargeId) {
        ApiResponse<LedgerTransactionDetailResponse> response;
        try {
            response = feignClient.findByReference(CHARGE, chargeId);
        } catch (FeignException e) {
            if (e.status() == NOT_FOUND && FeignErrors.hasErrorCode(e, NOT_FOUND_CODE)) {
                return SourceLookup.notFound();
            }
            return SourceLookup.unavailable(describe(e));
        }
        LedgerTransactionRecord record = response == null || !response.success() ? null : toRecord(response.data());
        if (record == null || !chargeId.equals(record.referenceId())) {
            return SourceLookup.unavailable("ledger response invalid");
        }
        return SourceLookup.found(record);
    }

    /** 충전 거래가 아니거나 필수 필드(transactionId·status·referenceId·entries와 항목 필드)가 없으면 null */
    private static LedgerTransactionRecord toRecord(LedgerTransactionDetailResponse item) {
        if (item == null || item.transactionId() == null || item.status() == null || item.referenceId() == null
                || !CHARGE.equals(item.referenceType()) || item.entries() == null) {
            return null;
        }
        List<LedgerEntryRecord> entries = new ArrayList<>();
        for (LedgerTransactionDetailResponse.Entry entry : item.entries()) {
            if (entry == null || entry.amount() == null) {
                return null;
            }
            entries.add(new LedgerEntryRecord(entry.ownerType(), entry.walletId(), entry.direction(),
                    entry.currency(), entry.amount(), entry.entryType()));
        }
        return new LedgerTransactionRecord(item.transactionId(), item.status(), item.referenceId(), entries);
    }

    private static String describe(FeignException e) {
        return e.getClass().getName() + " status=" + e.status();
    }
}
