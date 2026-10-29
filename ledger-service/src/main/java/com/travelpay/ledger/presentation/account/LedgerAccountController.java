package com.travelpay.ledger.presentation.account;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.ledger.application.account.LedgerAccountService;
import com.travelpay.ledger.application.account.dto.WalletAccountsResult;
import com.travelpay.ledger.presentation.account.dto.OpenWalletAccountsRequest;
import com.travelpay.ledger.presentation.account.dto.WalletAccountResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping("/internal/v1/ledger/accounts")
@RequiredArgsConstructor
public class LedgerAccountController {

    private final LedgerAccountService accountService;

    /** 새 계정이 생겼으면 201, 이미 모두 있었으면 200 */
    @PostMapping
    public ResponseEntity<ApiResponse<List<WalletAccountResponse>>> openWalletAccounts(
            @Valid @RequestBody OpenWalletAccountsRequest request) {
        WalletAccountsResult result = callLogging(request.walletId(),
                () -> accountService.openWalletAccounts(request.toCommand()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(WalletAccountResponse.listOf(result)));
    }

    @GetMapping
    public ApiResponse<List<WalletAccountResponse>> getWalletAccounts(@RequestParam UUID walletId) {
        WalletAccountsResult result = callLogging(walletId, () -> accountService.getWalletAccounts(walletId));
        return ApiResponse.success(WalletAccountResponse.listOf(result));
    }

    /** 트랜잭션 시작(커넥션 획득) 실패까지 잡히도록 서비스 프록시 바깥에서 기록. 예외 메시지는 SQL이 섞일 수 있어 남기지 않음 */
    private WalletAccountsResult callLogging(UUID walletId, Supplier<WalletAccountsResult> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("원장 지갑 계정 처리 실패 walletId={} exception={}", walletId, e.getClass().getName());
            throw e;
        }
    }
}
