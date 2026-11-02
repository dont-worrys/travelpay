package com.travelpay.wallet.infra.client.bank;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.dto.BankResult;
import feign.FeignException;
import feign.codec.EncodeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static com.travelpay.wallet.infra.client.FeignFailures.connectionRefused;
import static com.travelpay.wallet.infra.client.FeignFailures.error;
import static com.travelpay.wallet.infra.client.FeignFailures.response;
import static com.travelpay.wallet.infra.client.FeignFailures.timeout;
import static com.travelpay.wallet.infra.client.FeignFailures.undecodable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BankClientAdapterTest {

    private static final String REQUEST_ID = "charge-1";

    private final BankFeignClient feignClient = mock(BankFeignClient.class);
    private final BankClientAdapter adapter = new BankClientAdapter(feignClient);

    @Test
    void SUCCEEDED면_Succeeded() {
        UUID bankTxId = UUID.randomUUID();
        givenWithdraw(new BankTransactionResponse(bankTxId, REQUEST_ID, "DEBIT", "SUCCEEDED", null));

        assertEquals(new BankResult.Succeeded(bankTxId), withdraw());
    }

    @Test
    void FAILED면_실패_코드를_그대로_Failed() {
        givenWithdraw(new BankTransactionResponse(null, REQUEST_ID, "DEBIT", "FAILED", "INSUFFICIENT_BALANCE"));

        assertEquals(new BankResult.Failed("INSUFFICIENT_BALANCE"), withdraw());
    }

    @Test
    void 성공_응답_내용_이상은_Invalid() {
        givenWithdraw(new BankTransactionResponse(UUID.randomUUID(), "other", "DEBIT", "SUCCEEDED", null));
        assertInstanceOf(BankResult.Invalid.class, withdraw());

        givenWithdraw(new BankTransactionResponse(null, REQUEST_ID, "DEBIT", "SUCCEEDED", null));
        assertInstanceOf(BankResult.Invalid.class, withdraw());

        givenWithdraw(new BankTransactionResponse(UUID.randomUUID(), REQUEST_ID, "REFUND", "SUCCEEDED", null));
        assertInstanceOf(BankResult.Invalid.class, withdraw());
    }

    @ParameterizedTest
    @CsvSource({"409, BANK_DUPLICATE_REQUEST", "400, INVALID_INPUT", "422, BANK_REJECTED"})
    void 상대가_4xx로_거절하면_Invalid(int status, String code) {
        givenFailure(error(status, code));

        assertInstanceOf(BankResult.Invalid.class, withdraw());
        assertInstanceOf(BankResult.Invalid.class, refund());
    }

    static Stream<Arguments> unknownFailures() {
        return Stream.of(
                Arguments.of("2xx 본문 해석 실패", undecodable()),
                Arguments.of("시간 초과", timeout()),
                Arguments.of("연결 실패", connectionRefused()),
                Arguments.of("상대 500", response(500, "")),
                Arguments.of("상대 503", response(503, "")),
                Arguments.of("상대 504", response(504, "")),
                Arguments.of("요청 전송 전 실패", new EncodeException("요청 본문을 만들 수 없습니다.")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unknownFailures")
    void 거절이_아닌_실패는_Unknown(String reason, FeignException failure) {
        givenFailure(failure);

        assertInstanceOf(BankResult.Unknown.class, withdraw());
        assertInstanceOf(BankResult.Unknown.class, refund());
    }

    private void givenWithdraw(BankTransactionResponse data) {
        when(feignClient.withdraw(any())).thenReturn(ApiResponse.success(data));
    }

    private void givenFailure(FeignException failure) {
        when(feignClient.withdraw(any())).thenThrow(failure);
        when(feignClient.refund(any())).thenThrow(failure);
    }

    private BankResult withdraw() {
        return adapter.withdraw(REQUEST_ID, "004", "1234567890", "홍길동", 10_000);
    }

    private BankResult refund() {
        return adapter.refund(REQUEST_ID, UUID.randomUUID(), 10_000);
    }
}
