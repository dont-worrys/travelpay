package com.travelpay.wallet.presentation.wallet;

import com.travelpay.common.response.ApiResponse;
import com.travelpay.wallet.application.wallet.WalletService;
import com.travelpay.wallet.application.wallet.dto.InternalWalletBalancesResult;
import com.travelpay.wallet.application.wallet.dto.WalletResult;
import com.travelpay.wallet.presentation.wallet.dto.InternalWalletBalancesResponse;
import com.travelpay.wallet.presentation.wallet.dto.InternalWalletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 다른 서비스가 호출하는 내부 지갑 API. 각 API의 응답 필드와 오류는 호출하는 쪽과의 계약이므로 바꾸지 않는다 */
@Slf4j
@RestController
@RequestMapping("/internal/v1/wallets")
@RequiredArgsConstructor
public class WalletInternalController {

    private final WalletService walletService;

    /** 송금 파트와 합의한 지갑 조회. 응답 필드(walletId, status)와 지갑 없음 404는 계약 */
    @GetMapping
    public ApiResponse<InternalWalletResponse> getWallet(@RequestParam UUID userId) {
        WalletResult result;
        try {
            result = walletService.getWallet(userId);
        } catch (RuntimeException e) {
            log.warn("내부 지갑 조회 실패 userId={} exception={}", userId, e.getClass().getName());
            throw e;
        }
        return ApiResponse.success(InternalWalletResponse.from(result));
    }

    /** 환전 파트와 합의한 통화별 잔액 조회 (재환전용). 응답 필드(walletId, status, balances)와 지갑 없음 404는 계약 */
    @GetMapping("/balances")
    public ApiResponse<InternalWalletBalancesResponse> getBalances(@RequestParam UUID userId) {
        InternalWalletBalancesResult result;
        try {
            result = walletService.getInternalBalances(userId);
        } catch (RuntimeException e) {
            log.warn("내부 잔액 조회 실패 userId={} exception={}", userId, e.getClass().getName());
            throw e;
        }
        return ApiResponse.success(InternalWalletBalancesResponse.from(result));
    }
}
