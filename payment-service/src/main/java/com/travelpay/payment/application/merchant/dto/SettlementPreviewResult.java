package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.time.Instant;

/**
 * 정산 미리보기: 지금 정산할 수 있는 금액 전체(available), 그중 다음 정산 요청 한 번에 들어갈 몫(nextSettlement),
 * 환불 기간이 지나길 기다리는 금액(waiting). 정산 한 번에는 결제 최대 1,000건이 들어가므로 available이 더 크면
 * 남은 결제는 다음 요청에서 정산됩니다.
 * 조회 시점 기준이라 실제 요청 때는 환불·승인 시각 경과로 달라질 수 있습니다.
 * nextAvailableAt은 waiting 중 가장 먼저 정산 가능해지는 시각, 없으면 null.
 */
public record SettlementPreviewResult(
        CurrencyCode currency,
        Summary available,
        Summary nextSettlement,
        Summary waiting,
        Instant nextAvailableAt
) {

    public record Summary(int count, long amount) {
    }
}
