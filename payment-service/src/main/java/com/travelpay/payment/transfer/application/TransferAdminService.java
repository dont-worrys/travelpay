package com.travelpay.payment.transfer.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.response.PageResponse;
import com.travelpay.payment.transfer.client.LedgerTransactionResponse;
import com.travelpay.payment.transfer.client.LedgerTransferRecord;
import com.travelpay.payment.transfer.client.TransferLedgerReader;
import com.travelpay.payment.transfer.domain.Transfer;
import com.travelpay.payment.transfer.domain.TransferCurrency;
import com.travelpay.payment.transfer.domain.TransferErrorCode;
import com.travelpay.payment.transfer.domain.TransferFailureReason;
import com.travelpay.payment.transfer.domain.TransferRepository;
import com.travelpay.payment.transfer.domain.TransferStatus;
import com.travelpay.payment.transfer.domain.TransferStatusHistory;
import com.travelpay.payment.transfer.domain.TransferStatusHistoryRepository;
import com.travelpay.payment.transfer.dto.AdminTransferHistoryResponse;
import com.travelpay.payment.transfer.dto.AdminTransferOrderType;
import com.travelpay.payment.transfer.dto.AdminTransferResolutionResponse;
import com.travelpay.payment.transfer.dto.AdminTransferResolveRequest;
import com.travelpay.payment.transfer.dto.AdminTransferResponse;
import com.travelpay.payment.transfer.dto.AdminTransferSearch;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자(MASTER)의 송금 조회·MANUAL_REVIEW 확정을 처리합니다. 사용자 송금 조회({@link TransferService})와 달리 당사자 제한 없이 조회합니다.
 * 권한은 Gateway가 세션 확인 후 넘긴 역할로 판단하며, MASTER가 아니면 403입니다(결제 가맹점 관리 API와 같은 방식).
 * 조회 기록은 INFO 로그(관리자 ID·조건·송금 ID)로만 남기고, 상태 확정은 이력 테이블(transfer_status_history)에도 남깁니다.
 */
@Service
public class TransferAdminService {

    private static final Logger log = LoggerFactory.getLogger(TransferAdminService.class);

    private final TransferRepository transferRepository;
    private final TransferStatusHistoryRepository historyRepository;
    private final TransferLedgerReader ledgerReader;
    private final TransactionTemplate transactionTemplate;

    public TransferAdminService(TransferRepository transferRepository,
            TransferStatusHistoryRepository historyRepository,
            TransferLedgerReader ledgerReader,
            PlatformTransactionManager transactionManager) {
        this.transferRepository = transferRepository;
        this.historyRepository = historyRepository;
        this.ledgerReader = ledgerReader;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * MANUAL_REVIEW 송금을 운영자가 확정합니다. 운영자 입력을 그대로 믿지 않고 원장 거래 결과를 서버가 직접 조회해 맞는지 확인합니다.
     * <ul>
     *     <li>원장 POSTED → COMPLETED만 허용, ledgerTransactionId가 원장 거래 ID와 같아야 함</li>
     *     <li>원장 REJECTED → FAILED만 허용(실패 사유는 원장 거절 사유로 정함), ledgerTransactionId가 같아야 함</li>
     *     <li>원장 기록 없음 → PROCESSING만 허용(시도 횟수 0으로 되돌려 복구 스케줄러가 같은 reference로 재전송)</li>
     *     <li>원장 POSTED이면 원장에 기록된 항목이 이 송금의 원장 요청 항목(지갑·통화·방향·금액·entryType)과 같아야 함.
     *         다르면 같은 reference로 다른 내용이 기록된 것이라 확정하지 않음</li>
     *     <li>원장이 요청을 받지 않은 송금(LEDGER_REQUEST_NOT_ACCEPTED)도 원장 POSTED이고 항목이 같으면 COMPLETED로 확정
     *         (409는 userId 등 항목 밖의 값 차이로도 나며, 잔액은 항목으로만 바뀜)</li>
     *     <li>원장이 요청을 받지 않은 송금에 원장 REJECTED → FAILED 허용. 자금은 이동하지 않았고 재전송해도 계속 409라 처리할 방법이 없음.
     *         원장 거절 사유는 이전의 다른 요청 것일 수 있어 쓰지 않고 실패 사유를 NOT_PROCESSED로 정함(확정 이력과 같은 트랜잭션에 저장)</li>
     * </ul>
     * 원장 조회는 DB 트랜잭션 밖에서 하고, 조건부 UPDATE(MANUAL_REVIEW일 때만)와 이력 저장은 한 트랜잭션으로 묶습니다.
     * 원장 조회 실패(타임아웃·5xx 등)는 상태를 바꾸지 않고 common 규칙의 5xx로 응답합니다.
     * 이미 확정된 송금에 같은 요청이 다시 오면 기존 결과(현재 상태)를 200으로, 다른 요청이면 409를 돌려줍니다({@link #replay}).
     */
    public AdminTransferResponse resolve(AuthenticatedUser admin, UUID transferId, AdminTransferResolveRequest request) {
        requireMaster(admin);
        TransferStatus target = request.targetStatus();
        boolean settles = target == TransferStatus.COMPLETED || target == TransferStatus.FAILED;
        if (!settles && target != TransferStatus.PROCESSING) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        if (settles != (request.ledgerTransactionId() != null)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        Transfer transfer = findTransfer(transferId);
        String reason = request.reason().strip();
        if (transfer.getStatus() != TransferStatus.MANUAL_REVIEW) {
            return replay(admin, transfer, request, reason);
        }

        // 미리보기를 거쳤더라도 그 사이 원장 결과가 바뀌었을 수 있어 확정 때 다시 조회하고 확인합니다.
        TransferResolution resolution = TransferResolution.decide(transfer, ledgerReader.findTransfer(transferId));
        verify(transfer, resolution, request);
        TransferFailureReason failureReason = resolution.failureReason();
        UUID ledgerTransactionId = resolution.ledgerTransactionId();
        int attemptCount = target == TransferStatus.PROCESSING ? 0 : transfer.getAttemptCount();

        Boolean applied = transactionTemplate.execute(status -> {
            Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
            int updated = transferRepository.resolveManualReview(transferId, target, failureReason, attemptCount, now);
            if (updated == 0) {
                return false;
            }
            historyRepository.save(TransferStatusHistory.byOperator(transferId, TransferStatus.MANUAL_REVIEW, target,
                    reason, admin.userId(), ledgerTransactionId, now));
            return true;
        });
        if (!Boolean.TRUE.equals(applied)) {
            // 조회 이후 다른 요청이 먼저 확정했습니다. 같은 요청의 동시 재시도면 그 결과를, 아니면 409를 돌려줍니다.
            return replay(admin, findTransfer(transferId), request, reason);
        }
        log.info("관리자 송금 확정 adminId={} transferId={} reviewReason={} targetStatus={} ledgerTransactionId={}",
                admin.userId(), transferId, transfer.getReviewReason(), target, ledgerTransactionId);
        return AdminTransferResponse.from(findTransfer(transferId));
    }

    /**
     * MANUAL_REVIEW 송금을 원장 결과로 확정하면 어떻게 되는지 미리 보여줍니다. 상태·이력을 바꾸지 않습니다.
     * 판단은 확정({@link #resolve})과 같은 {@link TransferResolution}을 쓰며, 응답의 expectedStatus·ledgerTransactionId를
     * 그대로 확정 요청에 넣으면 됩니다(확정 때 원장을 다시 조회해 확인함).
     * MANUAL_REVIEW가 아니면 원장을 조회하지 않고 409 COMMON_CONFLICT, 원장 조회 실패는 common 규칙의 5xx입니다.
     * 원장 POSTED인데 항목이 송금과 다르면 200 + resolvable=false입니다.
     */
    public AdminTransferResolutionResponse previewResolution(AuthenticatedUser admin, UUID transferId) {
        requireMaster(admin);
        Transfer transfer = findTransfer(transferId);
        if (transfer.getStatus() != TransferStatus.MANUAL_REVIEW) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
        TransferResolution resolution = TransferResolution.decide(transfer, ledgerReader.findTransfer(transferId));
        log.info("관리자 송금 확정 미리보기 adminId={} transferId={} reviewReason={} expectedStatus={} ledgerTransactionId={}",
                admin.userId(), transferId, transfer.getReviewReason(), resolution.expectedStatus(),
                resolution.ledgerTransactionId());
        Optional<LedgerTransactionResponse> ledger = resolution.ledger().map(LedgerTransferRecord::result);
        return new AdminTransferResolutionResponse(
                transferId,
                transfer.getReviewReason(),
                new AdminTransferResolutionResponse.Ledger(
                        ledger.isPresent(),
                        ledger.map(LedgerTransactionResponse::transactionId).orElse(null),
                        ledger.map(LedgerTransactionResponse::status).orElse(null),
                        ledger.map(LedgerTransactionResponse::rejectReason).orElse(null),
                        resolution.entriesMatch()),
                resolution.resolvable(),
                resolution.expectedStatus(),
                resolution.failureReason(),
                resolution.expectedLedgerTransactionId());
    }

    /**
     * 이미 MANUAL_REVIEW가 아닌 송금에 확정 요청이 다시 온 경우를 처리합니다(응답 유실 후 재시도 등).
     * 마지막 운영자 확정 이력이 같은 운영자·같은 targetStatus·같은 ledgerTransactionId·같은 사유이면 같은 요청의 재시도로 보고
     * 원장을 다시 조회하지 않고 현재 송금 상태로 200을 돌려줍니다. 재처리(PROCESSING)로 돌린 뒤 복구가 끝났다면 현재 상태는 COMPLETED 등일 수 있습니다.
     * 그 밖(확정된 적 없음, 다른 운영자·다른 내용, 이후 다른 확정이 있음)은 409입니다.
     */
    private AdminTransferResponse replay(AuthenticatedUser admin, Transfer transfer,
            AdminTransferResolveRequest request, String reason) {
        boolean sameRequest = historyRepository
                .findFirstByTransferIdAndActorTypeOrderByCreatedAtDescIdDesc(
                        transfer.getId(), TransferStatusHistory.ActorType.OPERATOR)
                .filter(last -> last.getActorId().equals(admin.userId())
                        && last.getToStatus() == request.targetStatus()
                        && Objects.equals(last.getLedgerTransactionId(), request.ledgerTransactionId())
                        && last.getReason().equals(reason))
                .isPresent();
        if (!sameRequest) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
        log.info("관리자 송금 확정 재요청 adminId={} transferId={} targetStatus={} currentStatus={}",
                admin.userId(), transfer.getId(), request.targetStatus(), transfer.getStatus());
        return AdminTransferResponse.from(transfer);
    }

    /** 송금을 조회합니다. 없으면 404 TRANSFER_NOT_FOUND입니다. */
    private Transfer findTransfer(UUID transferId) {
        return transferRepository.findById(transferId)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.NOT_FOUND));
    }

    /** 송금의 상태 변경 이력을 기록 순서대로 조회합니다. 송금이 없으면 404입니다. */
    @Transactional(readOnly = true)
    public List<AdminTransferHistoryResponse> history(AuthenticatedUser admin, UUID transferId) {
        requireMaster(admin);
        log.info("관리자 송금 이력 조회 adminId={} transferId={}", admin.userId(), transferId);
        if (!transferRepository.existsById(transferId)) {
            throw new BusinessException(TransferErrorCode.NOT_FOUND);
        }
        return historyRepository.findByTransferIdOrderByCreatedAtAscIdAsc(transferId).stream()
                .map(AdminTransferHistoryResponse::from)
                .toList();
    }

    /**
     * 원장 결과로 정한 확정 판단({@link TransferResolution})과 확정 요청이 맞는지 확인합니다.
     * 확정할 수 없거나 targetStatus·ledgerTransactionId가 판단과 다르면 409 TRANSFER_RESOLUTION_MISMATCH입니다.
     */
    private static void verify(Transfer transfer, TransferResolution resolution, AdminTransferResolveRequest request) {
        if (Boolean.FALSE.equals(resolution.entriesMatch())) {
            // 같은 reference로 송금과 다른 내용이 원장에 기록되어 있습니다. 운영자 확정으로 해결할 수 없습니다.
            log.error("원장 거래 항목이 송금과 다름 transferId={} ledgerTransactionId={} reviewReason={}",
                    transfer.getId(), resolution.ledgerTransactionId(), transfer.getReviewReason());
        }
        if (!resolution.resolvable()
                || resolution.expectedStatus() != request.targetStatus()
                || !Objects.equals(resolution.expectedLedgerTransactionId(), request.ledgerTransactionId())) {
            throw new BusinessException(TransferErrorCode.RESOLUTION_MISMATCH);
        }
        if (resolution.failureReason() == TransferFailureReason.NOT_PROCESSED) {
            // 원장 거절 기록은 이전에 들어온 다른 요청의 것일 수 있어(원장은 기록을 고치지 않음) 그 사유를 쓰지 않습니다.
            // REJECTED는 잔액을 바꾸지 않으므로 FAILED로 확정해도 자금은 맞습니다.
            log.info("원장 미수락 송금 FAILED 확정 transferId={} ledgerTransactionId={} ledgerRejectReason={}",
                    transfer.getId(), resolution.ledgerTransactionId(),
                    resolution.ledger().map(record -> record.result().rejectReason()).orElse(null));
        }
    }

    /**
     * 조건에 맞는 송금을 조회합니다. 정렬은 생성 시각 기준이며 같은 시각은 ID로 같은 방향 정렬합니다.
     * page는 0부터, size는 1 이상이며 최대 크기를 넘으면 최대 크기로 조회합니다.
     * from은 포함, to는 미포함이며 from이 to보다 앞서야 합니다.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminTransferResponse> list(AuthenticatedUser admin, AdminTransferSearch search,
            AdminTransferOrderType orderType, int page, int size) {
        requireMaster(admin);
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        if (search.from() != null && search.to() != null && !search.from().isBefore(search.to())) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        if (search.currency() != null && !TransferCurrency.isSupported(search.currency())) {
            throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY);
        }
        AdminTransferOrderType order = orderType == null ? AdminTransferOrderType.LATEST : orderType;
        log.info("관리자 송금 목록 조회 adminId={} from={} to={} currency={} status={} userId={} orderType={} page={} size={}",
                admin.userId(), search.from(), search.to(), search.currency(), search.status(), search.userId(),
                order, page, size);

        Sort.Direction direction = order == AdminTransferOrderType.OLDEST ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(page, Math.min(size, TransferService.MAX_PAGE_SIZE),
                Sort.by(new Sort.Order(direction, "createdAt"), new Sort.Order(direction, "id")));
        Page<Transfer> transfers = transferRepository.findAll(matching(search), pageable);
        return PageResponse.from(transfers, AdminTransferResponse::from);
    }

    /** 송금 상세를 조회합니다. 없으면 404입니다. */
    @Transactional(readOnly = true)
    public AdminTransferResponse get(AuthenticatedUser admin, UUID transferId) {
        requireMaster(admin);
        log.info("관리자 송금 상세 조회 adminId={} transferId={}", admin.userId(), transferId);
        return transferRepository.findById(transferId)
                .map(AdminTransferResponse::from)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.NOT_FOUND));
    }

    /** 요청자가 MASTER가 아니면 403 COMMON_FORBIDDEN입니다. */
    private static void requireMaster(AuthenticatedUser user) {
        if (user.role() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    /** 목록 조회 조건 중 값이 있는 것만 AND로 묶습니다. 기간은 from 포함·to 미포함, userId는 송금인 또는 수취인입니다. */
    private static Specification<Transfer> matching(AdminTransferSearch search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (search.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), search.from()));
            }
            if (search.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), search.to()));
            }
            if (search.currency() != null) {
                predicates.add(cb.equal(root.get("currency"), search.currency()));
            }
            if (search.status() != null) {
                predicates.add(cb.equal(root.get("status"), search.status()));
            }
            if (search.userId() != null) {
                predicates.add(cb.or(
                        cb.equal(root.get("senderId"), search.userId()),
                        cb.equal(root.get("receiverId"), search.userId())));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
