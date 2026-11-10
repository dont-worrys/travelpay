package com.travelpay.wallet.infra.client.ledger;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.error.ErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.wallet.LedgerResponseException;
import com.travelpay.wallet.application.wallet.dto.LedgerAccount;
import com.travelpay.wallet.domain.WalletErrorCode;
import feign.FeignException;
import feign.codec.EncodeException;
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

import static com.travelpay.wallet.infra.client.FeignFailures.bodyReadTimeout;
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

    @Test
    void 요청_인코딩_실패는_502() {
        givenFindFails(new EncodeException("요청을 만들 수 없습니다."));

        assertFindFails(CommonErrorCode.BAD_GATEWAY);
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 501, 502, 503, 504, 505})
    void 원장_5xx는_원장_장애_503과_WARN(int status, CapturedOutput output) {
        givenFindFails(response(status, ""));
        assertFindFails(WalletErrorCode.WALLET_LEDGER_UNAVAILABLE);

        when(feignClient.openWalletAccounts(any())).thenThrow(response(status, ""));
        assertOpenFails(WalletErrorCode.WALLET_LEDGER_UNAVAILABLE);

        assertOutageLogged(output, "HTTP_" + status);
        assertTrue(output.getOut().contains("status=" + status));
    }

    @Test
    void 연결_실패는_원장_장애_503과_WARN(CapturedOutput output) {
        givenFindFails(connectionRefused());
        assertFindFails(WalletErrorCode.WALLET_LEDGER_UNAVAILABLE);

        when(feignClient.openWalletAccounts(any())).thenThrow(connectionRefused());
        assertOpenFails(WalletErrorCode.WALLET_LEDGER_UNAVAILABLE);

        assertOutageLogged(output, "CONNECTION_FAILURE");
        assertTrue(output.getOut().contains("exception=java.net.ConnectException"));
        assertFalse(output.getOut().contains("Connection refused"));
    }

    @Test
    void 시간_초과는_원장_장애_503과_WARN(CapturedOutput output) {
        givenFindFails(timeout());
        assertFindFails(WalletErrorCode.WALLET_LEDGER_UNAVAILABLE);

        when(feignClient.openWalletAccounts(any())).thenThrow(timeout());
        assertOpenFails(WalletErrorCode.WALLET_LEDGER_UNAVAILABLE);

        assertOutageLogged(output, "TIMEOUT");
        assertTrue(output.getOut().contains("exception=java.net.SocketTimeoutException"));
        assertFalse(output.getOut().contains("Read timed out"));
    }

    @Test
    void 본문을_읽다_시간_초과는_원장_장애_503과_WARN(CapturedOutput output) {
        givenFindFails(bodyReadTimeout());
        assertFindFails(WalletErrorCode.WALLET_LEDGER_UNAVAILABLE);

        when(feignClient.openWalletAccounts(any())).thenThrow(bodyReadTimeout());
        assertOpenFails(WalletErrorCode.WALLET_LEDGER_UNAVAILABLE);

        assertOutageLogged(output, "TIMEOUT");
        assertTrue(output.getOut().contains("status=200"));
        assertTrue(output.getOut().contains("exception=java.net.SocketTimeoutException"));
        assertFalse(output.getOut().contains("Read timed out"));
    }

    private void assertOutageLogged(CapturedOutput output, String type) {
        assertTrue(output.getOut().contains("WARN"));
        assertTrue(output.getOut().contains("type=" + type));
        assertTrue(output.getOut().contains("walletId=" + walletId));
        assertFalse(output.getOut().contains("ERROR"));
    }

    private void givenFindFails(FeignException failure) {
        when(feignClient.getWalletAccounts(any())).thenThrow(failure);
    }

    private void assertFindFails(ErrorCode errorCode) {
        BusinessException e = assertThrows(BusinessException.class, () -> adapter.findAccounts(walletId));
        assertEquals(errorCode, e.getErrorCode());
        assertEquals(errorCode.getStatus(), e.getErrorCode().getStatus());
    }

    private void assertOpenFails(ErrorCode errorCode) {
        BusinessException e = assertThrows(BusinessException.class, () -> adapter.openAccounts(walletId));
        assertEquals(errorCode, e.getErrorCode());
    }
}
