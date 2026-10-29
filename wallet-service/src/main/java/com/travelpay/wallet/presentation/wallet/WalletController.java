package com.travelpay.wallet.presentation.wallet;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.web.CurrentUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.wallet.WalletService;
import com.travelpay.wallet.application.wallet.dto.OpenWalletCommand;
import com.travelpay.wallet.application.wallet.dto.OpenWalletResult;
import com.travelpay.wallet.presentation.wallet.dto.OpenWalletResponse;
import com.travelpay.wallet.presentation.wallet.dto.WalletBalanceResponse;
import com.travelpay.wallet.presentation.wallet.dto.WalletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Slf4j
@RestController
@RequestMapping("/api/v1/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /** 이번 요청으로 활성화했으면 201, 이미 ACTIVE였으면 200 */
    @PostMapping
    public ResponseEntity<ApiResponse<OpenWalletResponse>> openWallet(@CurrentUser AuthenticatedUser user) {
        UUID userId = requireWalletUser(user);
        OpenWalletResult result = callLogging(userId,
                () -> walletService.openWallet(new OpenWalletCommand(userId)));
        return ResponseEntity.status(result.activatedNow() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.success(OpenWalletResponse.from(result)));
    }

    @GetMapping("/me")
    public ApiResponse<WalletResponse> getMyWallet(@CurrentUser AuthenticatedUser user) {
        UUID userId = requireWalletUser(user);
        return ApiResponse.success(WalletResponse.from(callLogging(userId, () -> walletService.getWallet(userId))));
    }

    @GetMapping("/me/balances")
    public ApiResponse<List<WalletBalanceResponse>> getMyBalances(@CurrentUser AuthenticatedUser user) {
        UUID userId = requireWalletUser(user);
        return ApiResponse.success(
                WalletBalanceResponse.listOf(callLogging(userId, () -> walletService.getBalances(userId))));
    }

    /** 지갑은 회원(USER)만 사용한다 */
    private UUID requireWalletUser(AuthenticatedUser user) {
        if (user.role() == AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return user.userId();
    }

    /** 트랜잭션 시작(커넥션 획득) 실패까지 잡히도록 서비스 프록시 바깥에서 기록. 예외 메시지는 SQL이 섞일 수 있어 남기지 않음 */
    private <T> T callLogging(UUID userId, Supplier<T> call) {
        try {
            return call.get();
        } catch (RuntimeException e) {
            log.warn("지갑 처리 실패 userId={} exception={}", userId, e.getClass().getName());
            throw e;
        }
    }
}
