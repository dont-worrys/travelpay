package com.travelpay.exchange.infrastructure.external.ledger;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.travelpay.exchange.domain.ledger.LedgerDirection;
import com.travelpay.exchange.domain.ledger.LedgerEntry;
import com.travelpay.exchange.domain.ledger.LedgerEntryType;
import com.travelpay.exchange.domain.ledger.LedgerPostResult;
import com.travelpay.exchange.domain.ledger.LedgerPostResult.InvalidKind;
import com.travelpay.exchange.domain.ledger.LedgerPosting;
import com.travelpay.exchange.domain.ledger.LedgerSystemAccount;
import com.travelpay.exchange.domain.money.Currency;
import com.travelpay.exchange.domain.money.Money;
import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.RetryableException;
import feign.codec.DecodeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LedgerClientAdapterTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WALLET_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID TX_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Request REQUEST = Request.create(Request.HttpMethod.POST,
            "http://ledger-service/internal/v1/ledger/transactions", Map.of(), null, StandardCharsets.UTF_8, null);
    private static final LedgerPosting POSTING = new LedgerPosting(ORDER_ID, USER_ID, List.of(
            LedgerEntry.wallet(WALLET_ID, new Money(Currency.KRW, 100000), LedgerDirection.DEBIT,
                    LedgerEntryType.EXCHANGE_OUT),
            LedgerEntry.system(LedgerSystemAccount.FX_POSITION, new Money(Currency.KRW, 100000),
                    LedgerDirection.CREDIT, LedgerEntryType.EXCHANGE_OUT)));

    private final LedgerFeignClient feignClient = mock(LedgerFeignClient.class);
    private final LedgerClientAdapter adapter = new LedgerClientAdapter(feignClient);

    @Test
    void postedWithTransactionId() {
        when(feignClient.register(any())).thenReturn(new LedgerTransactionResponse(true,
                new LedgerTransactionResponse.Data(TX_ID, "POSTED"), null));

        assertEquals(new LedgerPostResult.Posted(TX_ID), adapter.post(POSTING));
    }

    @Test
    void successWithoutPostedTransactionIsMalformed() {
        when(feignClient.register(any())).thenReturn(new LedgerTransactionResponse(true,
                new LedgerTransactionResponse.Data(null, "POSTED"), null));
        assertEquals(invalid(InvalidKind.MALFORMED_RESPONSE), adapter.post(POSTING));

        when(feignClient.register(any())).thenReturn(new LedgerTransactionResponse(true,
                new LedgerTransactionResponse.Data(TX_ID, "REJECTED"), null));
        assertEquals(invalid(InvalidKind.MALFORMED_RESPONSE), adapter.post(POSTING));
    }

    @Test
    void rejectionCodeOfUnprocessable() {
        when(feignClient.register(any())).thenThrow(error(422,
                "{\"success\":false,\"data\":null,\"error\":{\"code\":\"LEDGER_REJECTED_INSUFFICIENT_BALANCE\","
                        + "\"message\":\"잔액이 부족해 거래가 거절됐습니다.\",\"details\":[]}}"));

        assertEquals(new LedgerPostResult.Rejected("LEDGER_REJECTED_INSUFFICIENT_BALANCE"), adapter.post(POSTING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"success\":false,\"error\":{\"code\":\"COMMON_INVALID_REQUEST\"}}", "not json", ""})
    void unprocessableWithoutRejectionCodeIsMalformed(String body) {
        when(feignClient.register(any())).thenThrow(error(422, body));

        assertEquals(invalid(InvalidKind.MALFORMED_RESPONSE), adapter.post(POSTING));
    }

    @ParameterizedTest
    @CsvSource({"400, BAD_REQUEST", "409, CONFLICT", "404, MALFORMED_RESPONSE", "401, MALFORMED_RESPONSE"})
    void clientErrorsAreInvalid(int status, InvalidKind kind) {
        when(feignClient.register(any())).thenThrow(error(status, "{}"));

        assertEquals(invalid(kind), adapter.post(POSTING));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502, 503, 504})
    void serverErrorsAreUnknown(int status) {
        when(feignClient.register(any())).thenThrow(error(status, ""));

        assertEquals(new LedgerPostResult.Unknown("HTTP " + status), adapter.post(POSTING));
    }

    @Test
    void timeoutIsUnknown() {
        when(feignClient.register(any())).thenThrow(
                new RetryableException(-1, "Read timed out", Request.HttpMethod.POST, (Long) null, REQUEST));

        assertEquals(new LedgerPostResult.Unknown("RetryableException"), adapter.post(POSTING));
    }

    @Test
    void unreadableSuccessBodyIsMalformed() {
        when(feignClient.register(any())).thenThrow(new DecodeException(201, "bad json", REQUEST));

        assertEquals(invalid(InvalidKind.MALFORMED_RESPONSE), adapter.post(POSTING));
    }

    @Test
    void requestUsesExchangeReferenceAndOmitsUnusedAccountField() {
        JsonNode json = JsonMapper.builder().build().valueToTree(RegisterLedgerTransactionRequest.from(POSTING));

        assertEquals("EXCHANGE", json.get("referenceType").asString());
        assertEquals(ORDER_ID.toString(), json.get("referenceId").asString());
        assertEquals(USER_ID.toString(), json.get("userId").asString());
        JsonNode wallet = json.get("entries").get(0);
        assertEquals("USER", wallet.get("ownerType").asString());
        assertEquals(WALLET_ID.toString(), wallet.get("walletId").asString());
        assertFalse(wallet.has("accountType"));
        assertEquals("KRW", wallet.get("currency").asString());
        assertEquals("DEBIT", wallet.get("direction").asString());
        assertEquals(100000, wallet.get("amount").asLong());
        assertEquals("EXCHANGE_OUT", wallet.get("entryType").asString());
        JsonNode system = json.get("entries").get(1);
        assertEquals("SYSTEM", system.get("ownerType").asString());
        assertEquals("FX_POSITION", system.get("accountType").asString());
        assertFalse(system.has("walletId"));
    }

    private static LedgerPostResult invalid(InvalidKind kind) {
        return new LedgerPostResult.Invalid(kind);
    }

    /** Feign이 2xx가 아닌 응답을 받았을 때 던지는 예외와 같은 것을 만듭니다. */
    private static FeignException error(int status, String body) {
        Response response = Response.builder()
                .status(status)
                .reason("error")
                .request(REQUEST)
                .headers(Map.of())
                .body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("LedgerFeignClient#register(RegisterLedgerTransactionRequest)", response);
    }
}
