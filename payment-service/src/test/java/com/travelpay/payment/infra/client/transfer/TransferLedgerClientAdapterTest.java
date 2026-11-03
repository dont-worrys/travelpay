package com.travelpay.payment.infra.client.transfer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.UUID;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.transfer.client.LedgerTransactionRequest;
import com.travelpay.payment.application.transfer.client.LedgerTransactionResponse;
import com.travelpay.payment.application.transfer.client.LedgerTransactionResponse.RejectReason;
import com.travelpay.payment.application.transfer.client.TransferLedgerException;
import com.travelpay.payment.application.transfer.client.TransferLedgerException.Kind;
import com.travelpay.payment.domain.transfer.Transfer;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.Retryer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class TransferLedgerClientAdapterTest {

    private static final UUID TRANSACTION_ID = UUID.fromString("7d1c2b3a-4e5f-4a6b-8c7d-9e0f1a2b3c4d");
    private static final LedgerTransactionRequest REQUEST = LedgerTransactionRequest.of(processingTransfer());

    private TransferLedgerFeignClient feignClient;
    private TransferLedgerClientAdapter adapter;

    @BeforeEach
    void setUp() {
        feignClient = mock(TransferLedgerFeignClient.class);
        adapter = new TransferLedgerClientAdapter(feignClient);
    }

    /** 새로 기록(201)과 같은 reference 재요청(200) 모두 본문이 같아 POSTED로 해석합니다. */
    @Test
    void returnsPostedForSuccessResponse() {
        when(feignClient.register(REQUEST))
                .thenReturn(ApiResponse.success(new LedgerRegisterResponse(TRANSACTION_ID, "POSTED")));

        LedgerTransactionResponse result = adapter.register(REQUEST);

        assertEquals(LedgerTransactionResponse.posted(TRANSACTION_ID), result);
        verify(feignClient, times(1)).register(REQUEST);
    }

    @ParameterizedTest
    @CsvSource({
            "LEDGER_REJECTED_INSUFFICIENT_BALANCE, INSUFFICIENT_BALANCE",
            "LEDGER_REJECTED_WALLET_SUSPENDED, WALLET_SUSPENDED",
            "LEDGER_REJECTED_ACCOUNT_NOT_FOUND, ACCOUNT_NOT_FOUND"
    })
    void returnsRejectedForLedgerRejection(String code, RejectReason reason) {
        when(feignClient.register(REQUEST)).thenThrow(feignError(422, errorBody(code)));

        LedgerTransactionResponse result = adapter.register(REQUEST);

        assertEquals(LedgerTransactionResponse.Status.REJECTED, result.status());
        assertSame(reason, result.rejectReason());
        assertNull(result.transactionId());
    }

    @Test
    void reportsReadTimeoutAsResultUnknownWithoutRetry() {
        when(feignClient.register(REQUEST)).thenThrow(new RetryableException(-1, "Read timed out",
                Request.HttpMethod.POST, new SocketTimeoutException("Read timed out"), (Long) null, request()));

        var exception = assertThrows(TransferLedgerException.class, () -> adapter.register(REQUEST));

        assertSame(Kind.RESULT_UNKNOWN, exception.getKind());
        verify(feignClient, times(1)).register(REQUEST);
    }

    @Test
    void reportsConnectionFailureAsResultUnknown() {
        when(feignClient.register(REQUEST)).thenThrow(new RetryableException(-1, "Connection refused",
                Request.HttpMethod.POST, new ConnectException("Connection refused"), (Long) null, request()));

        var exception = assertThrows(TransferLedgerException.class, () -> adapter.register(REQUEST));

        assertSame(Kind.RESULT_UNKNOWN, exception.getKind());
    }

    /** 503에는 Eureka에 원장 인스턴스가 없을 때 로드밸런서가 만든 응답도 포함됩니다. */
    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void reportsServerErrorsAsResultUnknown(int status) {
        when(feignClient.register(REQUEST)).thenThrow(feignError(status, errorBody("COMMON_SERVICE_UNAVAILABLE")));

        var exception = assertThrows(TransferLedgerException.class, () -> adapter.register(REQUEST));

        assertSame(Kind.RESULT_UNKNOWN, exception.getKind());
    }

    @Test
    void reportsLoadBalancerWithoutInstanceAsResultUnknown() {
        when(feignClient.register(REQUEST)).thenThrow(feignError(503,
                "Load balancer does not contain an instance for the service ledger-service"));

        var exception = assertThrows(TransferLedgerException.class, () -> adapter.register(REQUEST));

        assertSame(Kind.RESULT_UNKNOWN, exception.getKind());
    }

    /** 확정 거절이 아닌 4xx는 원장에 반영되지 않았지만 요청을 잘못 만든 경우라 실패로 단정하지 않습니다. */
    @ParameterizedTest
    @CsvSource({
            "409, LEDGER_REFERENCE_CONFLICT",
            "400, COMMON_INVALID_REQUEST",
            "422, LEDGER_REJECTED_UNKNOWN_REASON",
            "422, COMMON_UNPROCESSABLE",
            "404, COMMON_NOT_FOUND"
    })
    void reportsOtherClientErrorsAsRequestNotAccepted(int status, String code) {
        when(feignClient.register(REQUEST)).thenThrow(feignError(status, errorBody(code)));

        var exception = assertThrows(TransferLedgerException.class, () -> adapter.register(REQUEST));

        assertSame(Kind.REQUEST_NOT_ACCEPTED, exception.getKind());
    }

    @Test
    void reportsRejectionWithoutReadableBodyAsRequestNotAccepted() {
        when(feignClient.register(REQUEST)).thenThrow(feignError(422, "<html>error</html>"));

        var exception = assertThrows(TransferLedgerException.class, () -> adapter.register(REQUEST));

        assertSame(Kind.REQUEST_NOT_ACCEPTED, exception.getKind());
    }

    /** 2xx는 원장이 기록했을 수 있으므로 본문을 해석할 수 없어도 실패로 보지 않습니다. */
    @Test
    void reportsUnreadableSuccessResponseAsResultUnknown() {
        assertResultUnknownFor(null);
        assertResultUnknownFor(new ApiResponse<>(true, null, null));
        assertResultUnknownFor(ApiResponse.success(new LedgerRegisterResponse(null, "POSTED")));
        assertResultUnknownFor(ApiResponse.success(new LedgerRegisterResponse(TRANSACTION_ID, "REJECTED")));
    }

    @Test
    void doesNotPutResponseBodyInExceptionMessage() {
        when(feignClient.register(REQUEST)).thenThrow(feignError(409,
                errorBody("LEDGER_REFERENCE_CONFLICT").replace("오류 메시지", "secret-detail")));

        var exception = assertThrows(TransferLedgerException.class, () -> adapter.register(REQUEST));

        assertFalse(exception.getMessage().contains("secret-detail"));
        assertEquals("원장 거래 등록 실패 status=409 code=LEDGER_REFERENCE_CONFLICT", exception.getMessage());
    }

    @Test
    void disablesAutomaticRetry() {
        assertSame(Retryer.NEVER_RETRY, new TransferLedgerFeignConfig().transferLedgerRetryer());
    }

    private void assertResultUnknownFor(ApiResponse<LedgerRegisterResponse> response) {
        when(feignClient.register(REQUEST)).thenReturn(response);

        var exception = assertThrows(TransferLedgerException.class, () -> adapter.register(REQUEST));

        assertSame(Kind.RESULT_UNKNOWN, exception.getKind());
    }

    private static Transfer processingTransfer() {
        Transfer transfer = Transfer.process(
                UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
                UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70"),
                UUID.fromString("0f8e6d4c-2b1a-4f9e-8d7c-6b5a4f3e2d1c"),
                UUID.fromString("1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"),
                3_000L, "KRW", null, "key", "hash");
        ReflectionTestUtils.setField(transfer, "id", UUID.fromString("2b3c4d5e-6f7a-4b8c-9d0e-1f2a3b4c5d6e"));
        return transfer;
    }

    private static String errorBody(String code) {
        return """
                {"success":false,"data":null,"error":{"code":"%s","message":"오류 메시지","details":[]}}
                """.formatted(code);
    }

    private static Request request() {
        return Request.create(Request.HttpMethod.POST,
                "http://ledger-service/internal/v1/ledger/transactions", Map.of(), null, UTF_8, null);
    }

    private static FeignException feignError(int status, String body) {
        Response response = Response.builder()
                .status(status)
                .reason("error")
                .request(request())
                .headers(Map.of())
                .body(body, UTF_8)
                .build();
        return FeignException.errorStatus("TransferLedgerFeignClient#register(LedgerTransactionRequest)", response);
    }
}
