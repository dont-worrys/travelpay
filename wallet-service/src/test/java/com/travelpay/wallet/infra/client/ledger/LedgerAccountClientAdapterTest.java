package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.wallet.LedgerResponseException;
import com.travelpay.wallet.application.wallet.dto.LedgerAccount;
import feign.FeignException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.travelpay.wallet.infra.client.FeignFailures.connectionRefused;
import static com.travelpay.wallet.infra.client.FeignFailures.error;
import static com.travelpay.wallet.infra.client.FeignFailures.response;
import static com.travelpay.wallet.infra.client.FeignFailures.timeout;
import static com.travelpay.wallet.infra.client.FeignFailures.undecodable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class LedgerAccountClientAdapterTest {

    private final LedgerFeignClient feignClient = mock(LedgerFeignClient.class);
    private final LedgerAccountClientAdapter adapter = new LedgerAccountClientAdapter(feignClient);

    private final UUID walletId = UUID.randomUUID();

    @Test
    void 정상_응답이면_통화_순으로_돌려준다() {
        when(feignClient.getWalletAccounts(walletId)).thenReturn(ApiResponse.success(List.of(
                new LedgerAccountResponse(UUID.randomUUID(), "CNY", 3L),
                new LedgerAccountResponse(UUID.randomUUID(), "KRW", 1L),
                new LedgerAccountResponse(UUID.randomUUID(), "JPY", 2L))));

        List<LedgerAccount> accounts = adapter.findAccounts(walletId).orElseThrow();

        assertEquals(List.of(1L, 2L, 3L), accounts.stream().map(LedgerAccount::balance).toList());
    }

    @Test
    void 통화가_빠진_2xx_응답은_LedgerResponseException() {
        when(feignClient.getWalletAccounts(walletId)).thenReturn(ApiResponse.success(List.of(
                new LedgerAccountResponse(UUID.randomUUID(), "KRW", 1L))));

        assertThrows(LedgerResponseException.class, () -> adapter.findAccounts(walletId));
    }

    @Test
    void 조회에서_계정_없음이면_empty() {
        when(feignClient.getWalletAccounts(walletId)).thenThrow(error(404, "LEDGER_ACCOUNT_NOT_FOUND"));

        assertEquals(Optional.empty(), adapter.findAccounts(walletId));
    }

    @Test
    void 개설에서_계정_없음_404는_502와_ERROR(CapturedOutput output) {
        when(feignClient.openWalletAccounts(any())).thenThrow(error(404, "LEDGER_ACCOUNT_NOT_FOUND"));

        assertOpenFails(CommonErrorCode.BAD_GATEWAY);
        assertTrue(output.getOut().contains("ERROR"));
        assertTrue(output.getOut().contains("walletId=" + walletId));
    }

    @ParameterizedTest
    @CsvSource({"404, NOT_FOUND", "400, INVALID_INPUT", "409, CONFLICT"})
    void 그_밖의_4xx는_502와_ERROR(int status, String code, CapturedOutput output) {
        givenFindFails(error(status, code));

        assertFindFails(CommonErrorCode.BAD_GATEWAY);
        assertTrue(output.getOut().contains("ERROR"));
        assertTrue(output.getOut().contains("walletId=" + walletId));
        assertTrue(output.getOut().contains("status=" + status));
        assertTrue(output.getOut().contains("code=" + code));
    }

    @Test
    void 본문_해석_실패는_502와_ERROR(CapturedOutput output) {
        givenFindFails(undecodable());

        assertFindFails(CommonErrorCode.BAD_GATEWAY);
        assertTrue(output.getOut().contains("ERROR"));
        assertTrue(output.getOut().contains("status=200"));
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 502})
    void 상대_500_502는_502이고_ERROR를_남기지_않는다(int status, CapturedOutput output) {
        givenFindFails(response(status, ""));

        assertFindFails(CommonErrorCode.BAD_GATEWAY);
        assertFalse(output.getOut().contains("ERROR"));
    }

    @Test
    void 상대_503과_연결_실패는_503() {
        givenFindFails(response(503, ""));
        assertFindFails(CommonErrorCode.SERVICE_UNAVAILABLE);

        when(feignClient.openWalletAccounts(any())).thenThrow(connectionRefused());
        assertOpenFails(CommonErrorCode.SERVICE_UNAVAILABLE);
    }

    @Test
    void 상대_504와_시간_초과는_504() {
        givenFindFails(response(504, ""));
        assertFindFails(CommonErrorCode.GATEWAY_TIMEOUT);

        when(feignClient.openWalletAccounts(any())).thenThrow(timeout());
        assertOpenFails(CommonErrorCode.GATEWAY_TIMEOUT);
    }

    private void givenFindFails(FeignException failure) {
        when(feignClient.getWalletAccounts(any())).thenThrow(failure);
    }

    private void assertFindFails(CommonErrorCode errorCode) {
        BusinessException e = assertThrows(BusinessException.class, () -> adapter.findAccounts(walletId));
        assertEquals(errorCode, e.getErrorCode());
    }

    private void assertOpenFails(CommonErrorCode errorCode) {
        BusinessException e = assertThrows(BusinessException.class, () -> adapter.openAccounts(walletId));
        assertEquals(errorCode, e.getErrorCode());
    }
}
