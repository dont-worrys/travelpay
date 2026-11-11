package com.travelpay.bank.presentation.fault;

import com.travelpay.common.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * 은행 코드별 출금 붙잡기를 실행 중에 켜고 끄는 측정용 내부 API. 세 API 모두 현재 붙잡기 목록을 돌려준다.
 * mock-bank.fault.enabled=true이고 prod 프로필이 아닐 때만 빈을 만든다(아니면 경로가 없어 404).
 */
@Slf4j
@RestController
@Profile("!prod")
@ConditionalOnProperty(prefix = "mock-bank.fault", name = "enabled", havingValue = "true", matchIfMissing = false)
@RequestMapping("/internal/v1/bank/faults/withdrawal-holds")
@RequiredArgsConstructor
public class BankWithdrawalHoldController {

    private static final String BANK_CODE = "\\d{3}";
    private static final String BANK_CODE_MESSAGE = "은행 코드는 숫자 3자리여야 합니다.";

    private final BankWithdrawalHolds holds;

    @GetMapping
    public ApiResponse<List<BankWithdrawalHolds.HoldStatus>> list() {
        return ApiResponse.success(holds.statuses());
    }

    @PutMapping("/{bankCode}")
    public ApiResponse<List<BankWithdrawalHolds.HoldStatus>> hold(
            @PathVariable @Pattern(regexp = BANK_CODE, message = BANK_CODE_MESSAGE) String bankCode,
            @Valid @RequestBody HoldRequest request) {
        holds.hold(bankCode, Duration.ofSeconds(request.delaySeconds()));
        log.warn("출금 붙잡기 켬 bankCode={} delaySeconds={}", bankCode, request.delaySeconds());
        return ApiResponse.success(holds.statuses());
    }

    @DeleteMapping("/{bankCode}")
    public ApiResponse<List<BankWithdrawalHolds.HoldStatus>> release(
            @PathVariable @Pattern(regexp = BANK_CODE, message = BANK_CODE_MESSAGE) String bankCode) {
        if (holds.release(bankCode)) {
            log.warn("출금 붙잡기 끔 bankCode={}", bankCode);
        }
        return ApiResponse.success(holds.statuses());
    }

    /** @param delaySeconds 출금을 처리 전에 붙잡는 시간. 지갑 읽기 시간 초과(5초)보다 길게 써야 결과 모름이 된다 */
    public record HoldRequest(
            @NotNull(message = "대기 시간(초)을 입력해 주세요.")
            @Min(value = 1, message = "대기 시간은 1초 이상이어야 합니다.")
            @Max(value = 600, message = "대기 시간은 600초 이하여야 합니다.")
            Integer delaySeconds) {
    }
}
