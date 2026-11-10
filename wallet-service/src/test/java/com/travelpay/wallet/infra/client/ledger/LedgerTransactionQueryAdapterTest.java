package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.reconciliation.ReconciliationSourceException;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LedgerTransactionQueryAdapterTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-01-02T00:00:00Z");
    private static final UUID WALLET_ID = UUID.randomUUID();

    private final LedgerTransactionFeignClient feignClient = mock(LedgerTransactionFeignClient.class);
    private final LedgerTransactionQueryAdapter adapter = new LedgerTransactionQueryAdapter(feignClient);

    @Test
    void 목록은_커서가_null일_때까지_모두_받는다() {
        LedgerTransactionDetailResponse a = posted(UUID.randomUUID());
        LedgerTransactionDetailResponse b = posted(UUID.randomUUID());
        when(feignClient.listByPeriod("CHARGE", FROM, TO, null, 100)).thenReturn(page(List.of(a), "c1"));
        when(feignClient.listByPeriod("CHARGE", FROM, TO, "c1", 100)).thenReturn(page(List.of(b), null));

        List<LedgerTransactionRecord> records = adapter.listChargeTransactions(FROM, TO);

        assertEquals(List.of(a.referenceId(), b.referenceId()),
                records.stream().map(LedgerTransactionRecord::referenceId).toList());
        assertEquals(10_000L, records.getFirst().chargeInEntry(WALLET_ID).orElseThrow().amount());
    }

    @Test
    void 커서가_반복되면_예외() {
        when(feignClient.listByPeriod("CHARGE", FROM, TO, null, 100)).thenReturn(page(List.of(), "c1"));
        when(feignClient.listByPeriod("CHARGE", FROM, TO, "c1", 100)).thenReturn(page(List.of(), "c1"));

        ReconciliationSourceException e = assertThrows(ReconciliationSourceException.class,
                () -> adapter.listChargeTransactions(FROM, TO));
        assertTrue(e.getMessage().contains("커서 반복"), e.getMessage());
    }

    @Test
    void 목록_조회_실패면_예외() {
        when(feignClient.listByPeriod("CHARGE", FROM, TO, null, 100)).thenThrow(error(503, "COMMON_SERVICE_UNAVAILABLE"));

        assertThrows(ReconciliationSourceException.class, () -> adapter.listChargeTransactions(FROM, TO));
    }

    @Test
    void REJECTED_거래는_항목_없이_받는다() {
        UUID chargeId = UUID.randomUUID();
        when(feignClient.findByReference("CHARGE", chargeId)).thenReturn(ApiResponse.success(
                new LedgerTransactionDetailResponse(UUID.randomUUID(), "REJECTED", "CHARGE", chargeId, List.of())));

        SourceLookup<LedgerTransactionRecord> lookup = adapter.findChargeTransaction(chargeId);

        LedgerTransactionRecord record = ((SourceLookup.Found<LedgerTransactionRecord>) lookup).record();
        assertEquals("REJECTED", record.status());
        assertTrue(record.entries().isEmpty());
    }

    @Test
    void 단건_404_LEDGER_TRANSACTION_NOT_FOUND만_NotFound() {
        UUID chargeId = UUID.randomUUID();
        when(feignClient.findByReference("CHARGE", chargeId)).thenThrow(error(404, "LEDGER_TRANSACTION_NOT_FOUND"));

        assertInstanceOf(SourceLookup.NotFound.class, adapter.findChargeTransaction(chargeId));
    }

    static Stream<FeignException> unavailableFailures() {
        return Stream.of(error(404, "LEDGER_ACCOUNT_NOT_FOUND"), response(404, ""),
                error(503, "COMMON_SERVICE_UNAVAILABLE"), error(400, "COMMON_INVALID_INPUT"),
                timeout(), connectionRefused(), undecodable());
    }

    @ParameterizedTest
    @MethodSource("unavailableFailures")
    void 단건_그_밖의_실패는_Unavailable(FeignException failure) {
        UUID chargeId = UUID.randomUUID();
        when(feignClient.findByReference("CHARGE", chargeId)).thenThrow(failure);

        assertInstanceOf(SourceLookup.Unavailable.class, adapter.findChargeTransaction(chargeId));
    }

    @Test
    void 단건_응답의_referenceId가_다르면_Unavailable() {
        UUID chargeId = UUID.randomUUID();
        when(feignClient.findByReference("CHARGE", chargeId)).thenReturn(ApiResponse.success(posted(UUID.randomUUID())));

        assertInstanceOf(SourceLookup.Unavailable.class, adapter.findChargeTransaction(chargeId));
    }

    private static LedgerTransactionDetailResponse posted(UUID chargeId) {
        return new LedgerTransactionDetailResponse(UUID.randomUUID(), "POSTED", "CHARGE", chargeId, List.of(
                new LedgerTransactionDetailResponse.Entry("SYSTEM", null, "CHARGE_SOURCE", "KRW", "DEBIT",
                        10_000L, "CHARGE_OUT"),
                new LedgerTransactionDetailResponse.Entry("USER", WALLET_ID, "WALLET", "KRW", "CREDIT",
                        10_000L, "CHARGE_IN")));
    }

    private static ApiResponse<LedgerTransactionPeriodResponse> page(List<LedgerTransactionDetailResponse> items,
                                                                    String nextCursor) {
        return ApiResponse.success(new LedgerTransactionPeriodResponse(items, nextCursor));
    }
}
