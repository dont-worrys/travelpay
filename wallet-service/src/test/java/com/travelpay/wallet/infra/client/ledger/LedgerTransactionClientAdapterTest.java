package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.charge.dto.LedgerPostResult;
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

class LedgerTransactionClientAdapterTest {

    private final LedgerTransactionFeignClient feignClient = mock(LedgerTransactionFeignClient.class);
    private final LedgerTransactionClientAdapter adapter = new LedgerTransactionClientAdapter(feignClient);

    @Test
    void POSTED면_Posted() {
        UUID transactionId = UUID.randomUUID();
        when(feignClient.register(any())).thenReturn(
                ApiResponse.success(new LedgerTransactionResponse(transactionId, "POSTED")));

        assertEquals(new LedgerPostResult.Posted(transactionId), post());
    }

    @Test
    void POSTED가_아닌_2xx는_Invalid() {
        when(feignClient.register(any())).thenReturn(
                ApiResponse.success(new LedgerTransactionResponse(UUID.randomUUID(), "PENDING")));

        assertInstanceOf(LedgerPostResult.Invalid.class, post());
    }

    @Test
    void 거절_접두어가_있는_422는_Rejected() {
        givenFailure(error(422, "LEDGER_REJECTED_ACCOUNT_NOT_FOUND"));

        assertEquals(new LedgerPostResult.Rejected("LEDGER_REJECTED_ACCOUNT_NOT_FOUND"), post());
    }

    @ParameterizedTest
    @CsvSource({"422, LEDGER_INVALID_AMOUNT", "422, REJECTED_LEDGER_X", "409, LEDGER_DUPLICATE_REFERENCE",
            "400, INVALID_INPUT", "404, LEDGER_REJECTED_ACCOUNT_NOT_FOUND"})
    void 그_밖의_4xx는_Invalid(int status, String code) {
        givenFailure(error(status, code));

        assertInstanceOf(LedgerPostResult.Invalid.class, post());
    }

    @Test
    void 본문_없는_422는_Invalid() {
        givenFailure(response(422, ""));

        assertInstanceOf(LedgerPostResult.Invalid.class, post());
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

        assertInstanceOf(LedgerPostResult.Unknown.class, post());
    }

    private void givenFailure(FeignException failure) {
        when(feignClient.register(any())).thenThrow(failure);
    }

    private LedgerPostResult post() {
        return adapter.postCharge(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 10_000);
    }
}
