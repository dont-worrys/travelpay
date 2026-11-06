package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.wallet.application.reconciliation.BankTransactionQueryClient;
import com.travelpay.wallet.application.reconciliation.ReconciliationSourceException;
import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 대사용 은행 거래 조회. 응답 내용은 실패 사유·로그에 싣지 않는다 (예외 클래스명·상태 코드만).
 * - 목록: page 0부터 totalPages까지. 어느 페이지든 실패·응답 이상·페이지 상한 초과면 ReconciliationSourceException
 * - 단건: 404 + BANK_TRANSACTION_NOT_FOUND만 NotFound. 그 밖의 실패(5xx·타임아웃·연결 실패·다른 4xx·코드 없는 404·응답 이상)는 Unavailable
 */
@Component
@RequiredArgsConstructor
public class BankTransactionQueryAdapter implements BankTransactionQueryClient {

    static final int PAGE_SIZE = 100;
    /** 무한 반복 방지. 100건 × 10,000페이지 = 100만 건 */
    static final int MAX_PAGES = 10_000;
    private static final int NOT_FOUND = 404;
    private static final String NOT_FOUND_CODE = "BANK_TRANSACTION_NOT_FOUND";

    private final BankFeignClient feignClient;

    @Override
    public List<BankTransactionRecord> listTransactions(Instant from, Instant to) {
        List<BankTransactionRecord> records = new ArrayList<>();
        for (int page = 0; ; page++) {
            if (page >= MAX_PAGES) {
                throw new ReconciliationSourceException("은행 거래 목록 페이지 상한 초과: " + MAX_PAGES);
            }
            PageResponse<BankTransactionDetailResponse> data = fetchPage(from, to, page);
            for (BankTransactionDetailResponse item : data.items()) {
                BankTransactionRecord record = toRecord(item);
                if (record == null) {
                    throw new ReconciliationSourceException("은행 거래 목록 응답 이상: 항목 필드 누락 page=" + page);
                }
                records.add(record);
            }
            if (page + 1 >= data.totalPages()) {
                return records;
            }
        }
    }

    private PageResponse<BankTransactionDetailResponse> fetchPage(Instant from, Instant to, int page) {
        ApiResponse<PageResponse<BankTransactionDetailResponse>> response;
        try {
            response = feignClient.listTransactions(from, to, page, PAGE_SIZE);
        } catch (FeignException e) {
            throw new ReconciliationSourceException("은행 거래 목록 조회 실패 page=" + page + ": "
                    + describe(e), e);
        }
        if (response == null || !response.success() || response.data() == null || response.data().items() == null) {
            throw new ReconciliationSourceException("은행 거래 목록 응답 이상: 본문 page=" + page);
        }
        return response.data();
    }

    @Override
    public SourceLookup<BankTransactionRecord> findTransaction(String requestId) {
        ApiResponse<BankTransactionDetailResponse> response;
        try {
            response = feignClient.findTransaction(requestId);
        } catch (FeignException e) {
            if (e.status() == NOT_FOUND && FeignErrors.hasErrorCode(e, NOT_FOUND_CODE)) {
                return SourceLookup.notFound();
            }
            return SourceLookup.unavailable(describe(e));
        }
        BankTransactionRecord record = response == null || !response.success() ? null : toRecord(response.data());
        if (record == null || !Objects.equals(requestId, record.requestId())) {
            return SourceLookup.unavailable("bank response invalid");
        }
        return SourceLookup.found(record);
    }

    /** 필수 필드(requestId·type·status·amount)가 없으면 null */
    private static BankTransactionRecord toRecord(BankTransactionDetailResponse item) {
        if (item == null || item.requestId() == null || item.type() == null || item.status() == null
                || item.amount() == null) {
            return null;
        }
        return new BankTransactionRecord(item.requestId(), item.bankTxId(), item.type(), item.status(),
                item.amount());
    }

    private static String describe(FeignException e) {
        return e.getClass().getName() + " status=" + e.status();
    }
}
