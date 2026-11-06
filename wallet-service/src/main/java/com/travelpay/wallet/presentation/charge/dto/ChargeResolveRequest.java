package com.travelpay.wallet.presentation.charge.dto;

import com.travelpay.wallet.domain.charge.ChargeStatusHistory;
import com.travelpay.wallet.domain.resolution.ResolutionAction;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 사유는 상태 이력 reason_detail(500자)에 그대로 남기므로 넘으면 400 (자르지 않음) */
public record ChargeResolveRequest(
        @NotNull(message = "보정 조치를 입력해 주세요.")
        ResolutionAction action,

        @NotBlank(message = "보정 사유를 입력해 주세요.")
        @Size(max = ChargeStatusHistory.REASON_DETAIL_MAX, message = "보정 사유는 500자 이하여야 합니다.")
        String reason,

        /** 연결할 대사 결과 (선택). 같은 충전의 결과여야 한다 */
        UUID reconciliationResultId
) {
}
