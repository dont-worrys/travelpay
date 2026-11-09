package com.travelpay.payment.application.merchant.dto;

import com.travelpay.payment.domain.payment.CurrencyCode;

import java.time.Instant;

/**
 * 정산 미리보기: 지금 요청하면 정산될 금액(available)과 환불 기간이 지나길 기다리는 금액(waiting).
 * 조회 시점 기준이라 실제 요청 때는 환불·승인 시각 경과로 달라질 수 있습니다.
 * nextAvailableAt은 waiting 중 가장 먼저 정산 가능해지는 시각, 없으면 null.
 */
public record SettlementPreviewResult(
        CurrencyCode currency,
        Summary available,
        Summary waiting,
        Instant nextAvailableAt
) {

    public record Summary(int count, long amount) {
    }
}
