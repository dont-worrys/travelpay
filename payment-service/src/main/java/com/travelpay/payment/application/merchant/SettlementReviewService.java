package com.travelpay.payment.application.merchant;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.payment.application.merchant.SettlementReviewRules.Decision;
import com.travelpay.payment.application.merchant.dto.SettlementManualReviewResult;
import com.travelpay.payment.application.merchant.dto.SettlementResolutionResult;
import com.travelpay.payment.application.merchant.dto.SettlementResolveResult;
import com.travelpay.payment.application.merchant.dto.SettlementReviewDetailResult;
import com.travelpay.payment.application.payment.AutoResolveOutcome;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerClient;
import com.travelpay.payment.application.payment.client.ledger.PaymentLedgerLookup;
import com.travelpay.payment.domain.merchant.Merchant;
import com.travelpay.payment.domain.merchant.MerchantRepository;
import com.travelpay.payment.domain.merchant.Settlement;
import com.travelpay.payment.domain.merchant.SettlementItemRepository;
import com.travelpay.payment.domain.merchant.SettlementRepository;
import com.travelpay.payment.domain.merchant.SettlementReviewResolution;
import com.travelpay.payment.domain.merchant.SettlementReviewResolutionRepository;
import com.travelpay.payment.domain.merchant.SettlementStatus;
import com.travelpay.payment.domain.payment.PaymentErrorCode;
import com.travelpay.payment.domain.payment.ReviewLedgerStatus;
import com.travelpay.payment.domain.payment.SettlementReviewReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MANUAL_REVIEW 정산을 MASTER가 처리합니다. 결과는 사람이 고르지 않고 원장 조회 결과로 정합니다(결제·환불 운영자 처리와 같은 방식).
 * 원장 조회는 트랜잭션 밖에서 하고, 상태 변경·항목 삭제·처리 기록은 한 트랜잭션으로 저장합니다.
 * 원장에 결과가 확정된 건(POSTED 항목 일치, REJECTED)은 스케줄러도 같은 규칙으로 자동 처리합니다(autoResolve).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementReviewService {

    // 자동 처리 기록의 설명 앞에 붙임
    static final String AUTO_DESCRIPTION_PREFIX = "자동 처리: ";
    private static final int MAX_PAGE_SIZE = 100;
    private static final Sort OLDEST_FIRST = Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"));
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final SettlementRepository settlementRepository;
    private final SettlementItemRepository settlementItemRepository;
    private final SettlementReviewResolutionRepository resolutionRepository;
    private final MerchantRepository merchantRepository;
    private final PaymentLedgerClient ledgerClient;
    private final TransactionOperations transactionOperations;

    public Page<SettlementManualReviewResult> getManualReviews(SettlementReviewReason reviewReason, int page, int size) {
        return settlementRepository.findManualReviews(reviewReason, pageRequest(page, size, OLDEST_FIRST))
                .map(SettlementManualReviewResult::from);
    }

    // 운영자 처리 기록 목록(최신순). 조건은 모두 선택
    public Page<SettlementResolutionResult> getResolutions(UUID actorId, UUID settlementId, int page, int size) {
        return resolutionRepository.search(actorId, settlementId, pageRequest(page, size, NEWEST_FIRST))
                .map(SettlementResolutionResult::from);
    }

    // 처리 전 확인용 단건 조회. 원장 조회에 실패해도 정산 정보는 보여 줌
    public SettlementReviewDetailResult getReview(UUID actorId, UUID settlementId) {
        Settlement settlement = findManualReview(settlementId);
        log.info("관리자 정산 MANUAL_REVIEW 단건 조회 actorId={} settlementId={}", actorId, settlementId);
        int paymentCount = settlementItemRepository.countBySettlementIds(List.of(settlementId))
                .getOrDefault(settlementId, 0L).intValue();
        PaymentLedgerLookup ledger;
        try {
            ledger = ledgerClient.findSettlementTransaction(settlementId);
        } catch (BusinessException e) {
            if (e.getErrorCode() != PaymentErrorCode.LEDGER_LOOKUP_UNAVAILABLE) {
                throw e;
            }
            log.warn("단건 조회 중 원장 조회 실패 settlementId={}", settlementId);
            return SettlementReviewDetailResult.of(settlement, paymentCount, null, null);
        }
        Decision decision = SettlementReviewRules.decide(settlement.getReviewReason(), ledger,
                expectedEntries(settlement));
        return SettlementReviewDetailResult.of(settlement, paymentCount, ledger,
                new SettlementReviewDetailResult.Expected(decision.resolvable(), decision.toStatus(),
                        decision.description()));
    }

    public SettlementResolveResult resolve(UUID actorId, UUID settlementId, String memo) {
        Settlement settlement = findManualReview(settlementId);
        SettlementReviewReason reviewReason = settlement.getReviewReason();
        PaymentLedgerLookup ledger = settlementLedgerForResolve(reviewReason, settlementId, actorId);
        Decision decision = SettlementReviewRules.decide(reviewReason, ledger, expectedEntries(settlement));
        if (!decision.resolvable()) {
            logCorrectionRequired(settlementId, reviewReason, ledger);
            throw new BusinessException(PaymentErrorCode.LEDGER_CORRECTION_REQUIRED);
        }

        SettlementResolveResult result;
        try {
            result = apply(settlement, reviewReason, ledger, decision, Objects.requireNonNull(actorId), memo);
        } catch (OptimisticLockingFailureException e) {
            // 원장을 조회하는 사이 다른 처리가 먼저 끝남
            throw new BusinessException(PaymentErrorCode.NOT_MANUAL_REVIEW);
        }
        // 커밋이 끝난 뒤에 남김(커밋 중 충돌로 롤백되면 남지 않음)
        log.info("정산 MANUAL_REVIEW 처리 settlementId={} reviewReason={} ledger={} → {} actorId={}",
                settlementId, reviewReason, ledger.status(), result.status(), actorId);
        return result;
    }

    /**
     * 스케줄러 자동 처리. 운영자 처리와 같은 규칙이지만 원장에 결과가 확정된 경우(POSTED 항목 일치, REJECTED)만
     * 처리하고, 원장에는 쓰지 않습니다(취소 없음). 원장 기록 없음·항목 불일치는 MANUAL_REVIEW 그대로 운영자에게 남깁니다.
     * 원장 조회 실패는 LEDGER_LOOKUP_UNAVAILABLE 예외를 그대로 던집니다.
     */
    public AutoResolveOutcome autoResolve(Settlement settlement) {
        UUID settlementId = settlement.getId();
        SettlementReviewReason reviewReason = settlement.getReviewReason();
        PaymentLedgerLookup ledger = ledgerClient.findSettlementTransaction(settlementId);
        if (ledger.status() == ReviewLedgerStatus.NOT_FOUND) {
            return AutoResolveOutcome.SKIPPED;
        }
        Decision decision = SettlementReviewRules.decide(reviewReason, ledger, expectedEntries(settlement));
        if (!decision.resolvable()) {
            // 운영자가 처리할 때까지 1분마다 같은 건을 다시 보므로 WARN으로 남김(운영자 처리에서 막히면 ERROR)
            log.warn("원장 보정 필요(자동 처리 건너뜀): 원장 거래 내용이 정산과 다름 settlementId={} reviewReason={} "
                            + "ledgerTransactionId={} ledgerEntries={}",
                    settlementId, reviewReason, ledger.transactionId(), ledger.entries());
            return AutoResolveOutcome.SKIPPED;
        }
        if (reviewReason == SettlementReviewReason.LEDGER_REQUEST_NOT_ACCEPTED
                && ledger.status() == ReviewLedgerStatus.POSTED) {
            log.warn("원장 요청 거부로 멈췄지만 원장에 같은 내용의 거래가 있어 자동 처리 settlementId={} ledgerTransactionId={}",
                    settlementId, ledger.transactionId());
        }

        SettlementResolveResult result;
        try {
            result = apply(settlement, reviewReason, ledger, decision, null, null);
        } catch (OptimisticLockingFailureException e) {
            return AutoResolveOutcome.ALREADY_RESOLVED;
        }
        log.info("정산 MANUAL_REVIEW 자동 처리 settlementId={} reviewReason={} ledger={} → {}",
                settlementId, reviewReason, ledger.status(), result.status());
        return AutoResolveOutcome.RESOLVED;
    }

    // 정산 상태 변경·항목 삭제·처리 기록을 한 트랜잭션으로. actorId가 없으면 스케줄러 자동 처리.
    // 그 사이 다른 처리가 먼저 끝나면 OptimisticLockingFailureException
    private SettlementResolveResult apply(Settlement settlement, SettlementReviewReason reviewReason,
                                          PaymentLedgerLookup ledger, Decision decision, UUID actorId, String memo) {
        UUID settlementId = settlement.getId();
        return Objects.requireNonNull(transactionOperations.execute(status -> {
            if (decision.toStatus() == SettlementStatus.PAID) {
                settlement.resolvePaid(ledger.transactionId());
                settlementRepository.save(settlement);
            } else {
                settlement.resolveFailed(decision.failureReason());
                settlementRepository.save(settlement);
                // 포함됐던 결제가 다음 정산 요청에서 다시 대상이 되고 환불도 가능해짐. 삭제 전에 위 변경이 먼저 반영됨
                settlementItemRepository.deleteAllBySettlementId(settlementId);
            }
            resolutionRepository.save(actorId == null
                    ? SettlementReviewResolution.ofSystem(settlementId, reviewReason, ledger.status(),
                            ledger.transactionId(), decision.toStatus(), AUTO_DESCRIPTION_PREFIX + decision.description())
                    : SettlementReviewResolution.of(settlementId, reviewReason, ledger.status(),
                            ledger.transactionId(), decision.toStatus(), actorId, decision.description(), memo));
            return new SettlementResolveResult(settlementId, decision.toStatus(), decision.failureReason(),
                    ledger.status(), ledger.transactionId());
        }));
    }

    private static void logCorrectionRequired(UUID settlementId, SettlementReviewReason reviewReason,
                                              PaymentLedgerLookup ledger) {
        log.error("원장 보정 필요: 원장 거래 내용이 정산과 다름 settlementId={} reviewReason={} "
                        + "ledgerTransactionId={} ledgerEntries={}",
                settlementId, reviewReason, ledger.transactionId(), ledger.entries());
    }

    // 재시도를 다 쓴 정산이 원장에 없으면 취소 기록을 먼저 남기고 그 응답으로 처리. FAILED 뒤 재요청은 새 reference라
    // 마지막 요청이 늦게 커밋되면 이중 지급이 되므로 막는다. 취소 여부를 모르면 503(상태 유지)
    private PaymentLedgerLookup settlementLedgerForResolve(SettlementReviewReason reviewReason, UUID settlementId,
                                                           UUID actorId) {
        PaymentLedgerLookup found = ledgerClient.findSettlementTransaction(settlementId);
        if (!SettlementReviewRules.requiresLedgerCancel(reviewReason, found)) {
            return found;
        }
        PaymentLedgerLookup afterCancel = ledgerClient.cancelSettlementTransaction(settlementId, actorId);
        log.info("정산 MANUAL_REVIEW 처리 전 원장 취소 settlementId={} result={}", settlementId, afterCancel.status());
        return afterCancel;
    }

    private Settlement findManualReview(UUID settlementId) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new BusinessException(PaymentErrorCode.SETTLEMENT_NOT_FOUND));
        if (settlement.getStatus() != SettlementStatus.MANUAL_REVIEW) {
            throw new BusinessException(PaymentErrorCode.NOT_MANUAL_REVIEW);
        }
        return settlement;
    }

    // 정산이 원장에 남겼어야 할 항목: 가맹점 원장 지갑 → 주인 지갑 (정산 요청 때와 같은 구성)
    private List<PaymentLedgerLookup.Entry> expectedEntries(Settlement settlement) {
        UUID merchantWalletId = merchantRepository.findById(settlement.getMerchantId())
                .map(Merchant::getLedgerWalletId)
                .orElseThrow(() -> new IllegalStateException(
                        "가맹점 원장 지갑을 찾을 수 없습니다. merchantId=" + settlement.getMerchantId()));
        String currency = settlement.getCurrency().name();
        return List.of(
                new PaymentLedgerLookup.Entry(merchantWalletId, currency, "DEBIT", settlement.getAmount(),
                        "SETTLEMENT_OUT"),
                new PaymentLedgerLookup.Entry(settlement.getOwnerWalletId(), currency, "CREDIT", settlement.getAmount(),
                        "SETTLEMENT_IN"));
    }

    private static Pageable pageRequest(int page, int size, Sort sort) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        return PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE), sort);
    }
}
