package com.travelpay.payment.infra.client.ledger;

import java.util.Map;
import java.util.UUID;

import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorResponse;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerRefundCommand;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerResult;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerTransactionCommand;
import com.travelpay.payment.domain.payment.CurrencyCode;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.Entry;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.RegisterRequest;
import com.travelpay.payment.infra.client.ledger.LedgerFeignApi.TransactionResponse;
import com.travelpay.payment.support.FeignFailures;
import feign.FeignException;
import feign.Request;
import feign.codec.DecodeException;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentLedgerFeignClientTest {

    private static final UUID PAYMENT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
    private static final UUID REFUND_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_WALLET_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MERCHANT_WALLET_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID TRANSACTION_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID REFUND_TRANSACTION_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final PaymentLedgerTransactionCommand PAYMENT = new PaymentLedgerTransactionCommand(
            PAYMENT_ID, USER_ID, USER_WALLET_ID, MERCHANT_WALLET_ID, CurrencyCode.KRW, 10_000L);
    private static final PaymentLedgerRefundCommand REFUND = new PaymentLedgerRefundCommand(
            REFUND_ID, TRANSACTION_ID, USER_ID, USER_WALLET_ID, MERCHANT_WALLET_ID, CurrencyCode.KRW, 10_000L);

    private LedgerFeignApi feignApi;
    private PaymentLedgerFeignClient ledgerClient;

    @BeforeEach
    void setUp() {
        feignApi = mock(LedgerFeignApi.class);
        ledgerClient = new PaymentLedgerFeignClient(feignApi);
    }

    @Test
    void sendsPaymentAsUserDebitAndMerchantCredit() {
        when(feignApi.register(any())).thenReturn(posted(TRANSACTION_ID));

        PaymentLedgerResult result = ledgerClient.createTransaction(PAYMENT);

        assertEquals(PaymentLedgerResult.approved(TRANSACTION_ID), result);
        RegisterRequest sent = captureRequest();
        assertEquals("PAYMENT", sent.referenceType());
        assertEquals(PAYMENT_ID, sent.referenceId());
        assertEquals(USER_ID, sent.userId());
        assertNull(sent.originalTransactionId());
        assertEquals(new Entry("USER", USER_WALLET_ID, "KRW", "DEBIT", 10_000L, "PAYMENT_OUT"), sent.entries().get(0));
        assertEquals(new Entry("USER", MERCHANT_WALLET_ID, "KRW", "CREDIT", 10_000L, "PAYMENT_IN"), sent.entries().get(1));
    }

    @Test
    void sendsRefundAsMerchantDebitAndUserCreditWithOriginalTransaction() {
        when(feignApi.register(any())).thenReturn(posted(REFUND_TRANSACTION_ID));

        PaymentLedgerResult result = ledgerClient.refundTransaction(REFUND);

        assertEquals(PaymentLedgerResult.approved(REFUND_TRANSACTION_ID), result);
        RegisterRequest sent = captureRequest();
        assertEquals("PAYMENT_REFUND", sent.referenceType());
        assertEquals(REFUND_ID, sent.referenceId());
        assertEquals(TRANSACTION_ID, sent.originalTransactionId());
        assertEquals(new Entry("USER", MERCHANT_WALLET_ID, "KRW", "DEBIT", 10_000L, "PAYMENT_REFUND_OUT"),
                sent.entries().get(0));
        assertEquals(new Entry("USER", USER_WALLET_ID, "KRW", "CREDIT", 10_000L, "PAYMENT_REFUND_IN"),
                sent.entries().get(1));
    }

    @ParameterizedTest
    @CsvSource({
            "LEDGER_REJECTED_INSUFFICIENT_BALANCE, 잔액이 부족합니다.",
            "LEDGER_REJECTED_WALLET_SUSPENDED, 사용할 수 없는 지갑입니다.",
            "LEDGER_REJECTED_ACCOUNT_NOT_FOUND, 결제할 수 있는 계정이 없습니다.",
            "LEDGER_REJECTED_NEW_REASON, 결제가 거절되었습니다."
    })
    void returnsDeclinedWithPaymentReasonInsteadOfLedgerCode(String ledgerCode, String expectedReason) {
        when(feignApi.register(any())).thenThrow(feignError(422, rejectedBody(ledgerCode)));

        assertEquals(PaymentLedgerResult.declined(expectedReason), ledgerClient.createTransaction(PAYMENT));
    }

    @ParameterizedTest
    @CsvSource({
            "LEDGER_REJECTED_INSUFFICIENT_BALANCE, 환불이 거절되었습니다.",
            "LEDGER_REJECTED_WALLET_SUSPENDED, 환불할 수 없는 계정입니다.",
            "LEDGER_REJECTED_ACCOUNT_NOT_FOUND, 환불할 수 있는 계정이 없습니다.",
            "LEDGER_REJECTED_NEW_REASON, 환불이 거절되었습니다."
    })
    void returnsDeclinedWithRefundReasonInsteadOfLedgerCode(String ledgerCode, String expectedReason) {
        when(feignApi.register(any())).thenThrow(feignError(422, rejectedBody(ledgerCode)));

        assertEquals(PaymentLedgerResult.declined(expectedReason), ledgerClient.refundTransaction(REFUND));
    }

    @Test
    void returnsUnknownWhenConnectionFails() {
        when(feignApi.register(any())).thenThrow(FeignFailures.connectionRefused());

        assertEquals(PaymentLedgerResult.unknown(), ledgerClient.createTransaction(PAYMENT));
    }

    @Test
    void returnsUnknownWhenLedgerTimesOut() {
        when(feignApi.register(any())).thenThrow(FeignFailures.readTimeout());

        assertEquals(PaymentLedgerResult.unknown(), ledgerClient.createTransaction(PAYMENT));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 504})
    void returnsUnknownWhenLedgerFailsWithServerError(int status) {
        when(feignApi.register(any())).thenThrow(feignError(status, ""));

        assertEquals(PaymentLedgerResult.unknown(), ledgerClient.refundTransaction(REFUND));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"success\":false,\"data\":null,\"error\":{\"code\":\"COMMON_INVALID_REQUEST\",\"message\":\"x\",\"details\":[]}}",
            "<html>error</html>",
            ""
    })
    void returnsNotAcceptedForUnexpected422(String body) {
        when(feignApi.register(any())).thenThrow(feignError(422, body));

        assertEquals(PaymentLedgerResult.notAccepted(), ledgerClient.createTransaction(PAYMENT));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 409})
    void returnsNotAcceptedForOtherClientErrors(int status) {
        when(feignApi.register(any())).thenThrow(feignError(status, ""));

        assertEquals(PaymentLedgerResult.notAccepted(), ledgerClient.createTransaction(PAYMENT));
        assertEquals(PaymentLedgerResult.notAccepted(), ledgerClient.refundTransaction(REFUND));
    }

    @Test
    void returnsUnknownWhenSuccessBodyCannotBeRead() {
        when(feignApi.register(any())).thenThrow(new DecodeException(200, "본문 해석 실패", successRequest()));

        assertEquals(PaymentLedgerResult.unknown(), ledgerClient.createTransaction(PAYMENT));
    }

    @Test
    void returnsUnknownWhenSuccessBodyIsUnexpected() {
        when(feignApi.register(any()))
                .thenReturn(ApiResponse.success(new TransactionResponse(null, "POSTED")))
                .thenReturn(ApiResponse.success(null))
                .thenReturn(new ApiResponse<>(false, null, ErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR)))
                .thenReturn(null);

        for (int i = 0; i < 4; i++) {
            assertEquals(PaymentLedgerResult.unknown(), ledgerClient.createTransaction(PAYMENT));
        }
    }

    private RegisterRequest captureRequest() {
        ArgumentCaptor<RegisterRequest> captor = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(feignApi).register(captor.capture());
        return captor.getValue();
    }

    private static String rejectedBody(String code) {
        return "{\"success\":false,\"data\":null,\"error\":{\"code\":\"" + code
                + "\",\"message\":\"원장 문구\",\"details\":[]}}";
    }

    private static ApiResponse<TransactionResponse> posted(UUID transactionId) {
        return ApiResponse.success(new TransactionResponse(transactionId, "POSTED"));
    }

    private static Request successRequest() {
        return Request.create(Request.HttpMethod.POST, "http://ledger-service/internal/v1/ledger/transactions",
                Map.of(), null, UTF_8, null);
    }

    private static FeignException feignError(int status, String body) {
        Request request = Request.create(Request.HttpMethod.POST, "http://ledger-service/internal/v1/ledger/transactions",
                Map.of(), null, UTF_8, null);
        Response response = Response.builder()
                .status(status)
                .reason("error")
                .request(request)
                .headers(Map.of())
                .body(body, UTF_8)
                .build();
        return FeignException.errorStatus("LedgerFeignApi#register", response);
    }
}
