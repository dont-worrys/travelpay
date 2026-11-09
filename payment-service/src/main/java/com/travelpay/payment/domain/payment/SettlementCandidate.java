package com.travelpay.payment.domain.payment;

import java.time.Instant;
import java.util.UUID;

// 정산 미리보기용: 아직 정산되지 않은 APPROVED 결제와 처음 승인된 시각
public record SettlementCandidate(UUID paymentId, Long amount, Instant approvedAt) {
}
