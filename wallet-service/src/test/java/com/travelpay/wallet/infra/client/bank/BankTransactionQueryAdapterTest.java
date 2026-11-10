package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.common.response.PageResponse;
import com.travelpay.wallet.application.reconciliation.ReconciliationSourceException;
import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.travelpay.wallet.infra.client.FeignFailures.connectionRefused;
import static com.travelpay.wallet.infra.client.FeignFailures.error;
import static com.travelpay.wallet.infra.client.FeignFailures.response;
import static com.travelpay.wallet.infra.client.FeignFailures.timeout;
import static com.travelpay.wallet.infra.client.FeignFailures.undecodable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BankTransactionQueryAdapterTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-02T00:00:00Z");

    private final BankFeignClient feignClient = mock(BankFeignClient.class);
    private final BankTransactionQueryAdapter adapter = new BankTransactionQueryAdapter(feignClient);

    @Test
    void 목록은_마지막_페이지까지_모두_받는다() {
        BankTransactionDetailResponse a = item("a");
        BankTransactionDetailResponse b = item("b");
        BankTransactionDetailResponse c = item("c");
        when(feignClient.listTransactions(FROM, TO, 0, 100)).thenReturn(page(List.of(a, b), 0, 3));
        when(feignClient.listTransactions(FROM, TO, 1, 100)).thenReturn(page(List.of(c), 1, 3));

        List<BankTransactionRecord> records = adapter.listTransactions(FROM, TO);

        verify(feignClient, times(2)).listTransactions(eq(FROM), eq(TO), anyInt(), eq(100));

        assertEquals(List.of("a", "b", "c"), records.stream().map(BankTransactionRecord::requestId).toList());
        assertEquals(10_000L, records.getFirst().amount());
    }

    @Test
    void 빈_목록은_첫_페이지만_받는다() {
        when(feignClient.listTransactions(FROM, TO, 0, 100)).thenReturn(page(List.of(), 0, 0));

        assertEquals(List.of(), adapter.listTransactions(FROM, TO));
        verify(feignClient).listTransactions(eq(FROM), eq(TO), eq(0), anyInt());
    }

    @Test
    void 중간_페이지_조회_실패면_예외() {
        when(feignClient.listTransactions(FROM, TO, 0, 100)).thenReturn(page(List.of(item("a"), item("b")), 0, 3));
        when(feignClient.listTransactions(FROM, TO, 1, 100)).thenThrow(timeout());

        ReconciliationSourceException e = assertThrows(ReconciliationSourceException.class,
                () -> adapter.listTransactions(FROM, TO));
        assertEquals("은행 거래 목록 조회 실패 page=1: feign.RetryableException status=-1", e.getMessage());
    }

    @Test
    void 목록_항목에_필수_필드가_없으면_예외() {
        when(feignClient.listTransactions(FROM, TO, 0, 100)).thenReturn(page(List.of(
                new BankTransactionDetailResponse(UUID.randomUUID(), "a", "DEBIT", "SUCCEEDED", null)), 0, 1));

        assertThrows(ReconciliationSourceException.class, () -> adapter.listTransactions(FROM, TO));
    }

    @Test
    void 단건을_찾으면_Found() {
        when(feignClient.findTransaction("a")).thenReturn(ApiResponse.success(item("a")));

        SourceLookup<BankTransactionRecord> lookup = adapter.findTransaction("a");

        BankTransactionRecord record = ((SourceLookup.Found<BankTransactionRecord>) lookup).record();
        assertEquals("a", record.requestId());
        assertEquals(10_000L, record.amount());
    }

    @Test
    void 단건_404_BANK_TRANSACTION_NOT_FOUND만_NotFound() {
        when(feignClient.findTransaction("a")).thenThrow(error(404, "BANK_TRANSACTION_NOT_FOUND"));

        assertInstanceOf(SourceLookup.NotFound.class, adapter.findTransaction("a"));
    }

    static Stream<FeignException> unavailableFailures() {
        return Stream.of(error(404, "COMMON_NOT_FOUND"), response(404, "<html>not found</html>"),
                error(503, "COMMON_SERVICE_UNAVAILABLE"), error(500, "COMMON_INTERNAL_ERROR"),
                error(400, "COMMON_INVALID_INPUT"), timeout(), connectionRefused(), undecodable());
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void 단건_그_밖의_실패는_Unavailable(FeignException failure) {
        when(feignClient.findTransaction("a")).thenThrow(failure);

        assertInstanceOf(SourceLookup.Unavailable.class, adapter.findTransaction("a"));
    }

    @Test
    void 단건_응답의_requestId가_다르면_Unavailable() {
        when(feignClient.findTransaction("a")).thenReturn(ApiResponse.success(item("b")));

        assertInstanceOf(SourceLookup.Unavailable.class, adapter.findTransaction("a"));
    }

    private static BankTransactionDetailResponse item(String requestId) {
        return new BankTransactionDetailResponse(UUID.randomUUID(), requestId, "DEBIT", "SUCCEEDED", 10_000L);
    }

    private static ApiResponse<PageResponse<BankTransactionDetailResponse>> page(
            List<BankTransactionDetailResponse> items, int page, long total) {
        int totalPages = (int) Math.ceil(total / 2.0);
        return ApiResponse.success(new PageResponse<>(items, page, 2, total, totalPages));
    }
}
