package com.travelpay.payment.application.transfer;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.util.PageableUtils;
import com.travelpay.payment.application.transfer.client.LedgerTransactionRequest;
import com.travelpay.payment.application.transfer.client.LedgerTransactionResponse;
import com.travelpay.payment.application.transfer.client.TransferLedgerClient;
import com.travelpay.payment.application.transfer.client.TransferLedgerException;
import com.travelpay.payment.application.transfer.client.TransferUserReader;
import com.travelpay.payment.application.transfer.client.TransferWallet;
import com.travelpay.payment.application.transfer.client.TransferWalletReader;
import com.travelpay.payment.application.transfer.dto.RecipientResult;
import com.travelpay.payment.application.transfer.dto.TransferCommand;
import com.travelpay.payment.application.transfer.dto.TransferResult;
import com.travelpay.payment.domain.transfer.Transfer;
import com.travelpay.payment.domain.transfer.TransferCurrency;
import com.travelpay.payment.domain.transfer.TransferDirection;
import com.travelpay.payment.domain.transfer.TransferErrorCode;
import com.travelpay.payment.domain.transfer.TransferFailureReason;
import com.travelpay.payment.domain.transfer.TransferRepository;
import com.travelpay.payment.domain.transfer.TransferReviewReason;
import com.travelpay.payment.domain.transfer.TransferStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 송금 요청·취소와 조회를 처리합니다.
 * 원장 거래 등록 구현({@link TransferLedgerClient})이 없으면 잔액을 변경하지 않고 REQUESTED 상태로 접수만 기록합니다.
 * 구현이 있으면 PROCESSING으로 저장한 뒤 원장에 거래 등록(송금인 차감·수취인 증가)을 요청하고 결과에 따라 COMPLETED·FAILED로 바꾸며,
 * 결과를 모르면 PROCESSING으로 남겨 복구 스케줄러({@link TransferRecoveryScheduler})가 대기 시간을 늘려 가며 같은 reference(송금 ID)로
 * 다시 요청합니다. 원장이 요청을 받아들이지 않거나(409·400 등) 결과 모름이 한도에 이르면 자동 처리를 멈추고 MANUAL_REVIEW로 둡니다.
 * 같은 Idempotency-Key 재요청은 원장을 다시 호출하지 않고 현재 상태로 응답합니다.
 * 잔액 검증·변경과 같은 reference의 중복 처리 방지는 원장이 보장합니다.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    private static final Set<String> LIST_SORT_FIELDS = Set.of("createdAt", "id");

    // 공백·제어 문자를 제외한 ASCII 1~100자이며, transfer.idempotency_key 길이와 맞춥니다.
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[\\x21-\\x7E]{1,100}$");

    // 복구 중 원장 결과를 연달아 이만큼 확인하지 못하면 원장 장애로 보고 남은 송금은 다음 주기로 넘깁니다. 결제 복구와 같은 값입니다.
    private static final int MAX_CONSECUTIVE_UNKNOWN = 3;

    // 원장 결과를 이만큼 확인하지 못하면 자동 처리를 멈추고 MANUAL_REVIEW로 둡니다. 지갑 충전 복구와 같은 값입니다.
    static final int MAX_UNKNOWN_ATTEMPTS = 5;

    private final TransferRepository transferRepository;
    private final TransferWalletReader walletReader;
    private final TransferUserReader userReader;
    private final Optional<TransferLedgerClient> ledgerClient;

    public TransferService(
            TransferRepository transferRepository,
            TransferWalletReader walletReader,
            TransferUserReader userReader,
            Optional<TransferLedgerClient> ledgerClient
    ) {
        this.transferRepository = transferRepository;
        this.walletReader = walletReader;
        this.userReader = userReader;
        this.ledgerClient = ledgerClient;
    }

    /**
     * 송금을 요청합니다. 같은 송금인·키·요청 내용의 재요청은 원장을 다시 호출하지 않고 현재 상태로 응답합니다.
     * 처리 중(PROCESSING) 송금의 재전송은 복구 스케줄러만 맡아, 재요청이 몰려도 원장 호출 간격이 유지됩니다.
     * 재요청은 처음 요청 때 검증을 통과한 송금이므로 지갑·회원을 다시 조회하지 않습니다.
     * 지갑·회원 조회·원장 호출(원격 호출) 동안 DB 트랜잭션을 잡지 않도록 메소드 전체를 트랜잭션으로 묶지 않습니다.
     */
    public TransferResult request(UUID senderId, String idempotencyKey, TransferCommand request) {
        if (idempotencyKey == null || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {
            throw new BusinessException(TransferErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        String memo = normalizeMemo(request.memo());
        String requestHash = TransferRequestHasher.hash(
                request.receiverId(), request.amount(), request.currency(), memo);

        var existing = transferRepository.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, senderId);
        }

        if (senderId.equals(request.receiverId())) {
            throw new BusinessException(TransferErrorCode.SELF_TRANSFER);
        }
        if (!TransferCurrency.isSupported(request.currency())) {
            throw new BusinessException(TransferErrorCode.UNSUPPORTED_CURRENCY);
        }
        // 회원당 지갑은 1개이고 통화별 계정은 원장이 지갑 ID와 통화로 찾으므로, 지갑 상태만 확인합니다.
        UUID sourceWalletId = walletReader.findActiveWalletId(senderId)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.SENDER_WALLET_UNAVAILABLE));
        // 송금인 회원 상태는 Gateway 인증 단계에서 확인하므로, 수취인만 ACTIVE 회원인지 확인합니다. 이름은 쓰지 않습니다.
        if (userReader.findRecipient(request.receiverId()).isEmpty()) {
            throw new BusinessException(TransferErrorCode.RECEIVER_UNAVAILABLE);
        }
        UUID targetWalletId = walletReader.findActiveWalletId(request.receiverId())
                .orElseThrow(() -> new BusinessException(TransferErrorCode.RECEIVER_UNAVAILABLE));

        // 원장을 호출하기 전에 PROCESSING으로 커밋해, 결과를 모르는 송금도 기록에 남깁니다.
        Transfer transfer = ledgerClient.isPresent()
                ? Transfer.process(senderId, request.receiverId(), sourceWalletId, targetWalletId,
                        request.amount(), request.currency(), memo, idempotencyKey, requestHash)
                : Transfer.request(senderId, request.receiverId(), sourceWalletId, targetWalletId,
                        request.amount(), request.currency(), memo, idempotencyKey, requestHash);
        Transfer saved;
        try {
            saved = transferRepository.saveAndFlush(transfer);
        } catch (DataIntegrityViolationException exception) {
            // 같은 키의 동시 요청이 먼저 저장되면 UNIQUE(sender_id, idempotency_key)에 걸립니다.
            Transfer concurrent = transferRepository.findBySenderIdAndIdempotencyKey(senderId, idempotencyKey)
                    .orElseThrow(() -> exception);
            return replay(concurrent, requestHash, senderId);
        }
        Transfer current = saved.getStatus() == TransferStatus.PROCESSING ? attempt(saved).transfer() : saved;
        return respond(current, senderId);
    }

    /** 송금 당사자가 아니면 송금의 존재를 알리지 않도록 없는 송금과 같은 404를 반환합니다. */
    @Transactional(readOnly = true)
    public TransferResult get(UUID userId, UUID transferId) {
        return transferRepository.findById(transferId)
                .filter(transfer -> transfer.isParticipant(userId))
                .map(transfer -> TransferResult.of(transfer, userId))
                .orElseThrow(() -> new BusinessException(TransferErrorCode.NOT_FOUND));
    }

    /**
     * 접수(REQUESTED) 상태의 송금을 취소합니다. REQUESTED는 원장 처리 없이 접수만 된 송금이라 자금 이동이 없어 복구할 금액이 없습니다.
     * 송금인만 취소할 수 있고 수취인·제3자에게는 없는 송금과 같은 404를 반환하며, 이미 취소된 송금은 현재 상태를 그대로 반환합니다.
     * 동시 요청은 조건부 UPDATE로 한 번만 상태가 바뀝니다.
     */
    @Transactional
    public TransferResult cancel(UUID senderId, UUID transferId) {
        transferRepository.cancelRequested(transferId, senderId, Instant.now().truncatedTo(ChronoUnit.MICROS));
        Transfer transfer = transferRepository.findById(transferId)
                .filter(found -> found.getSenderId().equals(senderId))
                .orElseThrow(() -> new BusinessException(TransferErrorCode.NOT_FOUND));
        // 처리 중·완료·실패 송금은 UPDATE 대상이 아니므로 여기서 거절됩니다.
        if (transfer.getStatus() != TransferStatus.CANCELLED) {
            throw new BusinessException(CommonErrorCode.CONFLICT);
        }
        return TransferResult.of(transfer, senderId);
    }

    /**
     * 송금 전 수취인을 확인합니다. ACTIVE 회원이 아니거나(없는 회원 포함) 지갑이 없으면 404,
     * 지갑이 송금할 수 없는 상태면 transferable=false를 반환합니다. 이름은 마스킹해서만 내보냅니다.
     * 송금 요청과 같은 기준(ACTIVE 회원·ACTIVE 지갑)으로 판단하며, 실제 송금 시점에 다시 검증합니다.
     */
    public RecipientResult findRecipient(UUID userId, UUID receiverId) {
        if (userId.equals(receiverId)) {
            throw new BusinessException(TransferErrorCode.SELF_TRANSFER);
        }
        // user-service가 없는 회원과 정지·종료 회원을 구분하지 않으므로 둘 다 같은 404로 응답합니다.
        String maskedName = userReader.findRecipient(receiverId)
                .map(recipient -> RecipientNameMasker.mask(recipient.realName()))
                .orElseThrow(() -> new BusinessException(TransferErrorCode.RECEIVER_NOT_FOUND));
        boolean transferable = walletReader.findWallet(receiverId)
                .map(TransferWallet::isActive)
                .orElseThrow(() -> new BusinessException(TransferErrorCode.RECEIVER_NOT_FOUND));
        return new RecipientResult(receiverId, maskedName, transferable);
    }

    /**
     * 생성 시각 내림차순으로 본인 관련 송금을 조회합니다. direction이 null이면 보낸·받은 내역을 모두 포함합니다.
     * page는 0부터 시작하고 size는 1 이상이어야 하며, 최대 크기를 넘으면 최대 크기로 조회합니다.
     */
    @Transactional(readOnly = true)
    public Page<TransferResult> list(UUID userId, TransferDirection direction, int page, int size) {
        if (page < 0 || size < 1) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        // 정렬은 고정이며, 생성 시각이 같은 항목은 ID로 순서를 정합니다.
        Pageable pageable = PageableUtils.toStablePageable(
                PageRequest.of(page, size, LIST_SORT), LIST_SORT_FIELDS, "id", MAX_PAGE_SIZE);
        boolean includeSent = direction != TransferDirection.RECEIVED;
        boolean includeReceived = direction != TransferDirection.SENT;

        return transferRepository.findByParticipant(userId, includeSent, includeReceived, pageable)
                .map(transfer -> TransferResult.of(transfer, userId));
    }

    /**
     * 재시도 대기 시간이 지난 처리 중 송금을 오래된 순으로 최대 limit건 선점해 원장에 다시 요청하고, 결과가 확정된 건수를 반환합니다.
     * 저장된 값으로 같은 reference를 재전송하고 결과는 조건부 UPDATE로 반영하므로, 다른 인스턴스와 겹쳐도 잔액과 상태는 한 번만 바뀝니다.
     * 결과를 모르면 시도 횟수를 늘려 다음 대기 시간 뒤로 미루고, 한도에 이르거나 원장이 요청을 받아들이지 않으면 MANUAL_REVIEW로 둡니다.
     * 원장이 연달아 응답하지 못하면 원장 장애로 보고 남은 송금은 다음 주기로 넘깁니다.
     */
    public int recoverDue(Instant now, int limit) {
        if (ledgerClient.isEmpty()) {
            return 0;
        }
        int settled = 0;
        int consecutiveUnknown = 0;
        for (Transfer target : transferRepository.findRecoveryTargets(now, limit)) {
            Outcome outcome;
            try {
                if (transferRepository.claimForRecovery(target.getId(), target.getUpdatedAt(), now()) == 0) {
                    // 다른 인스턴스가 먼저 선점했거나 그 사이 결과가 반영됐습니다.
                    continue;
                }
                outcome = attempt(target).outcome();
            } catch (RuntimeException exception) {
                // 한 건의 실패가 다른 송금 복구를 막지 않도록 로그만 남깁니다.
                log.warn("처리 중 송금 복구에 실패했습니다. transferId={}", target.getId(), exception);
                outcome = Outcome.UNKNOWN;
            }
            switch (outcome) {
                case SETTLED -> {
                    settled++;
                    consecutiveUnknown = 0;
                }
                case NOT_ACCEPTED -> consecutiveUnknown = 0;
                case UNKNOWN -> consecutiveUnknown++;
            }
            if (consecutiveUnknown >= MAX_CONSECUTIVE_UNKNOWN) {
                log.warn("원장 거래 등록 결과를 연달아 확인하지 못해 남은 송금 복구를 다음 주기로 미룹니다.");
                break;
            }
        }
        return settled;
    }

    /** 생성 시각이 기준 시각 이전인데 아직 처리 중인 송금 수와 오래된 순 ID 일부를 조회합니다. */
    @Transactional(readOnly = true)
    public StuckTransfers findStuckProcessing(Instant createdBefore, int sampleSize) {
        return new StuckTransfers(
                transferRepository.countProcessingCreatedBefore(createdBefore),
                transferRepository.findProcessingIdsCreatedBefore(createdBefore, sampleSize));
    }

    /** 자동 처리를 멈추고 운영자 확인을 기다리는(MANUAL_REVIEW) 송금 수입니다. */
    @Transactional(readOnly = true)
    public long countManualReview() {
        return transferRepository.countByStatus(TransferStatus.MANUAL_REVIEW);
    }

    /** 원장 거래 등록 한 번의 결과입니다. */
    private enum Outcome {
        /** 완료·실패로 확정됐습니다. */
        SETTLED,
        /** 원장이 요청을 받아들이지 않았습니다(409·400 등). 재요청해도 같은 결과라 자동 처리를 멈춥니다. */
        NOT_ACCEPTED,
        /** 결과를 확인하지 못했습니다(타임아웃·연결 실패·5xx·해석 불가·반영 실패). */
        UNKNOWN
    }

    /** 원장 거래 등록 뒤 다시 읽은 송금과 그 결과입니다. */
    private record AttemptResult(Transfer transfer, Outcome outcome) {
    }

    private TransferResult replay(Transfer transfer, String requestHash, UUID senderId) {
        if (!transfer.getRequestHash().equals(requestHash)) {
            throw new BusinessException(TransferErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return respond(transfer, senderId);
    }

    /** 원장이 거절한 송금은 재요청에도 저장된 실패 사유로 처음과 같은 422를 반환합니다. */
    private TransferResult respond(Transfer transfer, UUID senderId) {
        if (transfer.getStatus() == TransferStatus.FAILED) {
            throw new BusinessException(transfer.getFailureReason().errorCode());
        }
        return TransferResult.of(transfer, senderId);
    }

    /**
     * PROCESSING 송금의 원장 거래 등록을 한 번 요청하고, 결과를 반영한 뒤 다시 읽은 송금을 반환합니다.
     * 결과를 모르면 시도 횟수를 기록하고(한도에 이르면 MANUAL_REVIEW), 원장이 요청을 받아들이지 않으면 MANUAL_REVIEW로 둡니다.
     * 기록 중 오류가 나면 받은 송금을 그대로 반환하며, 복구 스케줄러가 대기 시간 뒤 다시 요청합니다.
     */
    private AttemptResult attempt(Transfer transfer) {
        Outcome outcome = registerOnLedger(transfer);
        try {
            switch (outcome) {
                case NOT_ACCEPTED -> {
                    if (transferRepository.markManualReview(transfer.getId(),
                            TransferReviewReason.LEDGER_REQUEST_NOT_ACCEPTED, now()) == 1) {
                        log.error("원장이 거래 등록 요청을 받아들이지 않아 자동 처리를 멈추고 확인 대상(MANUAL_REVIEW)으로 둡니다. "
                                + "transferId={}", transfer.getId());
                    }
                }
                case UNKNOWN -> recordUnknown(transfer);
                case SETTLED -> {
                }
            }
            return new AttemptResult(transferRepository.findById(transfer.getId()).orElseThrow(), outcome);
        } catch (RuntimeException exception) {
            log.error("송금 처리 결과를 기록하지 못했습니다. transferId={}, outcome={}", transfer.getId(), outcome, exception);
            return new AttemptResult(transfer, outcome);
        }
    }

    /** 결과 모름을 기록합니다. 다른 곳에서 먼저 바꿨으면(상태·시도 횟수가 다르면) 기록하지 않습니다. */
    private void recordUnknown(Transfer transfer) {
        boolean exhausted = transfer.getAttemptCount() + 1 >= MAX_UNKNOWN_ATTEMPTS;
        int updated = transferRepository.recordUnknownAttempt(transfer.getId(), transfer.getAttemptCount(),
                exhausted ? TransferStatus.MANUAL_REVIEW : TransferStatus.PROCESSING,
                exhausted ? TransferReviewReason.RETRY_EXHAUSTED : null, now());
        if (updated == 1 && exhausted) {
            log.error("원장 거래 등록 결과를 {}번 확인하지 못해 자동 처리를 멈추고 확인 대상(MANUAL_REVIEW)으로 둡니다. "
                    + "원장 반영 여부를 확인해야 합니다. transferId={}", MAX_UNKNOWN_ATTEMPTS, transfer.getId());
        }
    }

    /**
     * 원장 거래 등록을 요청하고 확정된 결과(완료·거절)를 조건부 UPDATE로 반영합니다.
     * 요청은 저장된 송금 값으로 만들어 재전송도 처음과 같은 reference·내용을 보내며, 원장이 같은 reference를 한 번만 처리합니다.
     * 결과를 모르면(예외·해석할 수 없는 응답·반영 실패) 실패로 단정하지 않습니다.
     */
    private Outcome registerOnLedger(Transfer transfer) {
        LedgerTransactionResponse result;
        try {
            result = ledgerClient.get().register(LedgerTransactionRequest.of(transfer));
        } catch (TransferLedgerException exception) {
            if (exception.getKind() == TransferLedgerException.Kind.REQUEST_NOT_ACCEPTED) {
                // 원장이 받아들이지 않은 요청(409·400 등)은 송금 요청을 잘못 만든 버그나 계약 불일치라 확인이 필요합니다.
                // 409는 같은 reference의 원장 거래가 이미 있다는 뜻일 수 있어 FAILED로 끝내지 않습니다.
                log.error("원장이 거래 등록 요청을 받아들이지 않았습니다. transferId={}", transfer.getId(), exception);
                return Outcome.NOT_ACCEPTED;
            }
            log.warn("원장 거래 등록 결과를 확인할 수 없습니다. transferId={}", transfer.getId(), exception);
            return Outcome.UNKNOWN;
        } catch (RuntimeException exception) {
            log.warn("원장 거래 등록 결과를 확인할 수 없습니다. transferId={}", transfer.getId(), exception);
            return Outcome.UNKNOWN;
        }
        TransferFailureReason failureReason = failureReasonOf(result);
        if (result == null || result.status() == null
                || (result.status() == LedgerTransactionResponse.Status.REJECTED && failureReason == null)) {
            log.error("원장 거래 등록 결과를 해석할 수 없습니다. transferId={}, result={}", transfer.getId(), result);
            return Outcome.UNKNOWN;
        }
        TransferStatus finished = failureReason == null ? TransferStatus.COMPLETED : TransferStatus.FAILED;
        try {
            // 다른 곳에서 먼저 반영했으면 0행이 바뀌며, 호출한 쪽은 다시 읽은 상태를 따릅니다.
            transferRepository.finishProcessing(transfer.getId(), finished, failureReason, now());
            return Outcome.SETTLED;
        } catch (RuntimeException exception) {
            // 원장 처리 결과는 확정됐지만 반영하지 못했으므로 결과 모름으로 두고, 복구의 재전송(원장 멱등)으로 반영합니다.
            log.error("원장 거래 등록 결과를 반영하지 못했습니다. transferId={}, result={}",
                    transfer.getId(), result, exception);
            return Outcome.UNKNOWN;
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 원장 거절 사유를 송금 실패 사유로 바꿉니다. 거절이 아니거나 사유가 없으면 null입니다. */
    private static TransferFailureReason failureReasonOf(LedgerTransactionResponse result) {
        if (result == null || result.status() != LedgerTransactionResponse.Status.REJECTED
                || result.rejectReason() == null) {
            return null;
        }
        return switch (result.rejectReason()) {
            case INSUFFICIENT_BALANCE -> TransferFailureReason.INSUFFICIENT_BALANCE;
            case WALLET_SUSPENDED, ACCOUNT_NOT_FOUND -> TransferFailureReason.ACCOUNT_UNAVAILABLE;
        };
    }

    private static String normalizeMemo(String memo) {
        if (memo == null) {
            return null;
        }
        String stripped = memo.strip();
        return stripped.isEmpty() ? null : stripped;
    }
}
