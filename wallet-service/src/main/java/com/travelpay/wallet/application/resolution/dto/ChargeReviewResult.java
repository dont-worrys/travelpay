package com.travelpay.wallet.application.resolution.dto;

import com.travelpay.wallet.domain.Currency;
import com.travelpay.wallet.domain.charge.ChargeStatus;
import com.travelpay.wallet.domain.resolution.ResolutionAction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 보정 전 확인 결과. 지금 은행·원장 사실과 그 사실로 가능한 조치·불가 사유. 아무것도 바꾸지 않으며 실행 허가가 아니다
 * (실행은 POST resolve가 다시 조회해 판단한다).
 *
 * @param manualReviewAt      MANUAL_REVIEW 전환 시각 (충전 updated_at)
 * @param notFoundConfirmedAt 이 시각부터 NotFound를 "없음"으로 인정
 * @param blockers            조치와 무관하게 판정할 수 없는 이유 (금액 불일치·환불 거절 등)
 */
public record ChargeReviewResult(UUID chargeId, ChargeStatus status, long amount, Currency currency,
                                 Instant manualReviewAt, Instant notFoundConfirmedAt, Instant checkedAt,
                                 SourceFact withdrawal, SourceFact refund, SourceFact ledger,
                                 List<ResolutionAction> availableActions, List<String> blockers,
                                 List<UnavailableAction> unavailableActions) {

    public ChargeReviewResult {
        availableActions = List.copyOf(availableActions);
        blockers = List.copyOf(blockers);
        unavailableActions = List.copyOf(unavailableActions);
    }

    /** 조치별 불가 사유 (blockers 포함) */
    public record UnavailableAction(ResolutionAction action, List<String> reasons) {

        public UnavailableAction {
            reasons = List.copyOf(reasons);
        }
    }
}
