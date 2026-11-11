package com.travelpay.payment.application.merchant;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.application.payment.PaymentService;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerResult;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerSettlementCommand;
import com.travelpay.payment.application.payment.client.wallet.PaymentWallet;
import com.travelpay.payment.application.payment.client.wallet.PaymentWalletClient;
import com.travelpay.payment.application.merchant.dto.SettlementDetailResult;
import com.travelpay.payment.application.merchant.dto.SettlementPreviewResult;
import com.travelpay.payment.application.payment.dto.RecoveryBatchResult;
import com.travelpay.payment.application.merchant.dto.SettlementResult;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.payment.Payment;
import com.travelpay.payment.domain.payment.PaymentDeclinedException;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.PaymentRepository;
import com.travelpay.payment.domain.payment.Refund;
import com.travelpay.payment.domain.payment.RefundRepository;
import com.travelpay.payment.domain.payment.RefundStatus;
import com.travelpay.payment.domain.payment.SettlementCandidate;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementItem;
import com.travelpay.payment.domain.merchant.SettlementItemRepository;
import com.travelpay.payment.domain.merchant.SettlementRepository;
import com.travelpay.payment.domain.merchant.SettlementStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementService {

    // 환불 기간 경계에서 고객 환불 신청과 정산이 겹치지 않도록 두는 여유 시간
    static final Duration CUTOFF_MARGIN = Duration.ofHours(1);
    private static final int MAX_CONSECUTIVE_UNKNOWN = 3;
    private static final int MAX_UNKNOWN_ATTEMPTS = 5;
    // 정산 한 번에 넣는 결제 수 상한. 잠금 범위·트랜잭션 크기·IN 목록 크기를 일정하게 유지
    static final int MAX_PAYMENTS_PER_SETTLEMENT = 1_000;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final MerchantRepository merchantRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final PaymentWalletClient walletClient;
    private final PaymentLedgerClient ledgerClient;
    private final TransactionOperations transactionOperations;

    public SettlementResult requestSettlement(AuthenticatedUser user, UUID merchantId, String idempotencyKey) {
        Merchant merchant = findAccessibleMerchant(user, merchantId);

        // 같은 요청자·같은 키의 정산이 이미 있으면 재요청
        Optional<Settlement> existing = settlementRepository.findByRequestedByAndIdempotencyKey(
                user.userId(), idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), merchantId);
        }

        // 원장 계정이 없는(PENDING) 가맹점은 정산할 돈이 없음. 정지(SUSPENDED) 가맹점은 정산함
        if (merchant.getLedgerWalletId() == null) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_NOT_ACTIVE);
        }

        // 정산 받을 주인 지갑. 없거나 사용할 수 없으면 정산을 만들지 않음
        PaymentWallet ownerWallet = walletClient.findWallet(merchant.getOwnerUserId())
                .filter(PaymentWallet::isActive)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.SETTLEMENT_WALLET_NOT_AVAILABLE));

        // 대상 결제를 모아 정산과 항목을 저장
        CreatedSettlement created;
        try {
            created = transactionOperations.execute(status ->
                    createSettlement(merchant, user.userId(), idempotencyKey, ownerWallet.walletId()));
        } catch (DataIntegrityViolationException e) {
            // 같은 키의 동시 요청이 먼저 저장됐으면 그 정산으로 응답, 아니면 다른 요청이 같은 결제를 먼저 정산한 것
            Optional<Settlement> sameKey = settlementRepository.findByRequestedByAndIdempotencyKey(
                    user.userId(), idempotencyKey);
            if (sameKey.isPresent()) {
                return replay(sameKey.get(), merchantId);
            }
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }

        // 원장에 정산 거래를 보내고 결과 반영
        Settlement settled = settle(Objects.requireNonNull(created).settlement(), merchant.getLedgerWalletId());
        if (settled.getStatus() == SettlementStatus.FAILED) {
            throw new PaymentDeclinedException(PaymentErrorCode.SETTLEMENT_DECLINED, settled.getFailureReason());
        }
        return SettlementResult.from(settled, created.paymentCount());
    }

    // 정산 미리보기: 정산 요청과 같은 기준으로 지금 정산될 금액과 환불 기간을 기다리는 금액을 나눔.
    // 정산 요청과 달리 결제 행을 잠그지 않아 환불을 막지 않습니다.
    public SettlementPreviewResult previewSettlement(AuthenticatedUser user, UUID merchantId) {
        Merchant merchant = findAccessibleMerchant(user, merchantId);

        Instant now = Instant.now();
        Duration waitAfterApproval = PaymentService.REFUND_PERIOD.plus(CUTOFF_MARGIN);
        Instant approvedBefore = now.minus(waitAfterApproval);
        List<SettlementCandidate> candidates = paymentRepository.findSettlementCandidates(merchantId);
        Map<UUID, Long> refunded = completedRefundAmounts(
                candidates.stream().map(SettlementCandidate::paymentId).toList());

        int availableCount = 0;
        long availableAmount = 0;
        int nextCount = 0;
        long nextAmount = 0;
        int waitingCount = 0;
        long waitingAmount = 0;
        Instant nextAvailableAt = null;
        // 후보는 정산 대상과 같은 결제 생성 순이라, 정산 가능한 것 중 앞의 MAX_PAYMENTS_PER_SETTLEMENT건이 다음 요청에 들어감
        for (SettlementCandidate candidate : candidates) {
            long amount = candidate.amount() - refunded.getOrDefault(candidate.paymentId(), 0L);
            if (amount <= 0) {
                continue;
            }
            if (candidate.approvedAt().isBefore(approvedBefore)) {
                availableCount++;
                availableAmount += amount;
                if (nextCount < MAX_PAYMENTS_PER_SETTLEMENT) {
                    nextCount++;
                    nextAmount += amount;
                }
            } else {
                waitingCount++;
                waitingAmount += amount;
                Instant availableAt = candidate.approvedAt().plus(waitAfterApproval);
                if (nextAvailableAt == null || availableAt.isBefore(nextAvailableAt)) {
                    nextAvailableAt = availableAt;
                }
            }
        }
        return new SettlementPreviewResult(merchant.getCurrency(),
                new SettlementPreviewResult.Summary(availableCount, availableAmount),
                new SettlementPreviewResult.Summary(nextCount, nextAmount),
                new SettlementPreviewResult.Summary(waitingCount, waitingAmount),
                nextAvailableAt);
    }

    // 가맹점 정산 목록
    public Page<SettlementResult> getSettlements(AuthenticatedUser user, UUID merchantId, int page, int size) {
        findAccessibleMerchant(user, merchantId);
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Page<Settlement> settlements = settlementRepository.findPageByMerchantId(merchantId,
                PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), LIST_SORT));
        Map<UUID, Long> counts = settlementItemRepository.countBySettlementIds(
                settlements.map(Settlement::getId).getContent());
        return settlements.map(settlement ->
                SettlementResult.from(settlement, counts.getOrDefault(settlement.getId(), 0L).intValue()));
    }

    // 가맹점 정산 상세: 포함된 결제와 금액. 다른 가맹점의 정산이면 404
    public SettlementDetailResult getSettlement(AuthenticatedUser user, UUID merchantId, UUID settlementId) {
        findAccessibleMerchant(user, merchantId);
        Settlement settlement = settlementRepository.findById(settlementId)
                .filter(found -> found.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.SETTLEMENT_NOT_FOUND));
        List<SettlementItem> items = settlementItemRepository.findAllBySettlementId(settlementId);
        return SettlementDetailResult.from(settlement, items);
    }

    // 재시도 대기가 지난 REQUESTED 정산을 선점해 같은 정산 ID로 원장에 다시 보냄
    public RecoveryBatchResult recoverSettlements(Instant now, int limit) {
        int consecutiveUnknown = 0;
        List<Settlement> targets = settlementRepository.findRecoveryTargets(now, limit);
        for (Settlement target : targets) {
            boolean unknown;
            try {
                if (settlementRepository.claimForRecovery(target.getId(), target.getUpdatedAt(), Instant.now()) == 0) {
                    // 다른 인스턴스가 먼저 선점했거나 그 사이 결과가 반영됨
                    continue;
                }
                // 선점으로 수정 시각·버전이 바뀌었을 수 있어 다시 읽어 보냄
                Settlement claimed = settlementRepository.findById(target.getId()).orElseThrow();
                // 첫 요청과 같은 값으로 보내도록 가맹점 원장 지갑을 다시 읽음(원장 지갑 ID가 가맹점 ID와 같다고 가정하지 않음)
                UUID merchantWalletId = merchantRepository.findById(claimed.getMerchantId())
                        .map(Merchant::getLedgerWalletId)
                        .orElseThrow(() -> new IllegalStateException(
                                "가맹점 원장 지갑을 찾을 수 없습니다. merchantId=" + claimed.getMerchantId()));
                Settlement after = settle(claimed, merchantWalletId);
                // 결과 모름(REQUESTED 유지 또는 5회째로 MANUAL_REVIEW)만 원장 장애 신호로 셈
                unknown = after.getStatus() == SettlementStatus.REQUESTED
                        || after.getReviewReason() == SettlementReviewReason.RETRY_EXHAUSTED;
            } catch (RuntimeException e) {
                log.warn("정산 복구 실패 settlementId={}", target.getId(), e);
                unknown = true;
            }
            consecutiveUnknown = unknown ? consecutiveUnknown + 1 : 0;
            if (consecutiveUnknown >= MAX_CONSECUTIVE_UNKNOWN) {
                log.warn("원장 응답 불가로 정산 복구를 다음 주기로 미룹니다.");
                return new RecoveryBatchResult(targets.size(), true);
            }
        }
        return new RecoveryBatchResult(targets.size(), false);
    }

    // 생성 후 기준 시각이 지나도 REQUESTED인 정산 수와 오래된 순 ID
    public long countStuckSettlements(Instant createdBefore) {
        return settlementRepository.countRequestedCreatedBefore(createdBefore);
    }

    public long countManualReviewSettlements() {
        return settlementRepository.countManualReviews();
    }

    public List<UUID> findStuckSettlementIds(Instant createdBefore, int sampleSize) {
        return settlementRepository.findRequestedIdsCreatedBefore(createdBefore, sampleSize);
    }

    private CreatedSettlement createSettlement(Merchant merchant, UUID requestedBy, String idempotencyKey,
                                               UUID ownerWalletId) {
        // (승인 시각 + 환불 기간 + 여유 시간)이 지금보다 이른 결제만
        Instant cutoffAt = Instant.now();
        Instant approvedBefore = cutoffAt.minus(PaymentService.REFUND_PERIOD).minus(CUTOFF_MARGIN);
        // 한 번에 최대 MAX_PAYMENTS_PER_SETTLEMENT건(결제 생성 순). 나머지는 다음 요청에서 정산
        List<Payment> targets = paymentRepository.findSettlementTargets(merchant.getId(), approvedBefore,
                MAX_PAYMENTS_PER_SETTLEMENT);
        if (targets.isEmpty()) {
            throw new BusinessException(PaymentErrorCode.NO_SETTLEMENT_TARGET);
        }

        // 항목 금액 = 결제 금액 - 완료된 환불 합계. APPROVED 결제에는 진행 중인 환불이 없음
        Map<UUID, Long> refunded = completedRefundAmounts(targets.stream().map(Payment::getId).toList());

        List<PaymentAmount> amounts = new ArrayList<>();
        for (Payment payment : targets) {
            long amount = payment.getAmount() - refunded.getOrDefault(payment.getId(), 0L);
            if (amount <= 0) {
                // 남은 금액이 없으면 REFUNDED여야 함. 데이터 이상이라 이 결제만 빼고 정산
                log.error("정산 대상 결제의 남은 금액이 0 이하 paymentId={} amount={}", payment.getId(), amount);
                continue;
            }
            amounts.add(new PaymentAmount(payment.getId(), amount));
        }
        if (amounts.isEmpty()) {
            throw new BusinessException(PaymentErrorCode.NO_SETTLEMENT_TARGET);
        }
        long total = amounts.stream().mapToLong(PaymentAmount::amount).sum();

        Settlement settlement = settlementRepository.save(Settlement.request(merchant.getId(), requestedBy,
                idempotencyKey, merchant.getOwnerUserId(), ownerWalletId, merchant.getCurrency(), total, cutoffAt));
        settlementItemRepository.saveAll(amounts.stream()
                .map(target -> SettlementItem.of(target.paymentId(), settlement.getId(), target.amount()))
                .toList());
        return new CreatedSettlement(settlement, amounts.size());
    }

    // 결제별 완료된 환불 합계
    // 미리보기는 결제 수에 상한이 없어 IN 목록을 나눠 조회(JDBC 바인드 파라미터 한도 방지)
    private Map<UUID, Long> completedRefundAmounts(List<UUID> paymentIds) {
        Map<UUID, Long> refunded = new HashMap<>();
        for (int from = 0; from < paymentIds.size(); from += MAX_PAYMENTS_PER_SETTLEMENT) {
            List<UUID> chunk = paymentIds.subList(from, Math.min(from + MAX_PAYMENTS_PER_SETTLEMENT, paymentIds.size()));
            for (Refund refund : refundRepository.findAllByPaymentIdIn(chunk)) {
                if (refund.getStatus() == RefundStatus.COMPLETED) {
                    refunded.merge(refund.getPaymentId(), refund.getAmount(), Long::sum);
                }
            }
        }
        return refunded;
    }

    // 원장에 정산 거래를 보내고 결과를 반영한 정산을 돌려줌. 결과를 모르면 REQUESTED(5회째면 MANUAL_REVIEW), 받아들이지 않으면 MANUAL_REVIEW
    private Settlement settle(Settlement settlement, UUID merchantWalletId) {
        PaymentLedgerResult result = ledgerClient.settlementTransaction(new PaymentLedgerSettlementCommand(
                settlement.getId(),
                settlement.getOwnerUserId(),
                merchantWalletId,
                settlement.getOwnerWalletId(),
                settlement.getCurrency(),
                settlement.getAmount()));

        switch (result.status()) {
            case UNKNOWN -> {
                return recordUnknown(settlement);
            }
            case NOT_SENT -> {
                // 원장 서킷이 열려 보내지 않음. 재시도 횟수는 그대로 두고 REQUESTED 유지(복구 스케줄러가 다시 요청)
                return settlement;
            }
            case NOT_ACCEPTED -> {
                return markManualReview(settlement);
            }
            default -> {
            }
        }

        try {
            return Objects.requireNonNull(transactionOperations.execute(status -> applyResult(settlement, result)));
        } catch (OptimisticLockingFailureException e) {
            // 같은 정산을 복구 스케줄러가 먼저 반영함. 반영된 정산을 다시 읽어 반환
            return settlementRepository.findById(settlement.getId())
                    .orElseThrow(() -> new IllegalStateException("반영된 정산을 다시 찾을 수 없습니다."));
        }
    }

    private Settlement applyResult(Settlement settlement, PaymentLedgerResult result) {
        if (result.status() == PaymentLedgerResult.Status.APPROVED) {
            settlement.paid(result.transactionId());
            return settlementRepository.save(settlement);
        }
        // 거절: 항목을 지워 포함됐던 결제가 다음 요청에서 다시 대상이 되게 함
        settlement.fail(result.declineReason());
        Settlement saved = settlementRepository.save(settlement);
        settlementItemRepository.deleteAllBySettlementId(settlement.getId());
        return saved;
    }

    // 결과 모름 기록. 5회째(첫 요청 포함)면 MANUAL_REVIEW. 항목은 지우지 않음(원장에 반영됐을 수 있어 이중 지급 방지)
    // 기록 중 오류가 나면 받은 정산을 그대로 반환하고, 스케줄러가 다음에 다시 시도
    private Settlement recordUnknown(Settlement settlement) {
        boolean exhausted = settlement.getAttemptCount() + 1 >= MAX_UNKNOWN_ATTEMPTS;
        try {
            int updated = settlementRepository.recordUnknownAttempt(settlement.getId(), settlement.getAttemptCount(),
                    exhausted ? SettlementStatus.MANUAL_REVIEW : SettlementStatus.REQUESTED,
                    exhausted ? SettlementReviewReason.RETRY_EXHAUSTED : null, Instant.now());
            if (updated == 1 && exhausted) {
                log.error("원장 결과를 {}번 확인하지 못해 정산 자동 처리를 멈춤 settlementId={}",
                        MAX_UNKNOWN_ATTEMPTS, settlement.getId());
            }
            return settlementRepository.findById(settlement.getId()).orElse(settlement);
        } catch (RuntimeException e) {
            log.warn("정산 결과 모름 기록 실패 settlementId={}", settlement.getId(), e);
            return settlement;
        }
    }

    // 원장이 요청을 받아들이지 않음. 다시 보내도 같은 결과라 MANUAL_REVIEW로 멈춤(항목 유지)
    private Settlement markManualReview(Settlement settlement) {
        try {
            if (settlementRepository.markManualReview(settlement.getId(),
                    SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, Instant.now()) == 1) {
                log.error("원장이 정산 요청을 받아들이지 않아 자동 처리를 멈춤 settlementId={}", settlement.getId());
            }
            return settlementRepository.findById(settlement.getId()).orElse(settlement);
        } catch (RuntimeException e) {
            log.error("정산 MANUAL_REVIEW를 기록하지 못함 settlementId={}", settlement.getId(), e);
            return settlement;
        }
    }

    private SettlementResult replay(Settlement existing, UUID merchantId) {
        if (!existing.isSameRequest(merchantId)) {
            throw new BusinessException(PaymentErrorCode.SETTLEMENT_IDEMPOTENCY_KEY_REUSED);
        }
        // 원장 결과를 다시 묻지 않고 현재 상태로 응답. 결과 모름 재전송은 복구 스케줄러만 함
        if (existing.getStatus() == SettlementStatus.FAILED) {
            throw new PaymentDeclinedException(PaymentErrorCode.SETTLEMENT_DECLINED, existing.getFailureReason());
        }
        return SettlementResult.from(existing, settlementItemRepository.findAllBySettlementId(existing.getId()).size());
    }

    private Merchant findAccessibleMerchant(AuthenticatedUser user, UUID merchantId) {
        Merchant merchant = merchantRepository.findById(merchantId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.MERCHANT_NOT_FOUND));
        if (user.role() != AuthRole.MASTER && !merchant.getOwnerUserId().equals(user.userId())) {
            throw new BusinessException(PaymentErrorCode.MERCHANT_NOT_FOUND);
        }
        return merchant;
    }

    private record CreatedSettlement(Settlement settlement, int paymentCount) {
    }

    private record PaymentAmount(UUID paymentId, long amount) {
    }
}
