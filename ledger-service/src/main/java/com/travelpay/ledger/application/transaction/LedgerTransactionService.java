package com.travelpay.ledger.application.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.ledger.application.transaction.dto.CancelTransactionCommand;
import com.travelpay.ledger.application.transaction.dto.CancelTransactionResult;
import com.travelpay.ledger.application.transaction.dto.LedgerEntryResult;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.application.transaction.dto.TransactionDetailResult;
import com.travelpay.ledger.application.transaction.dto.TransactionPeriodQuery;
import com.travelpay.ledger.application.transaction.dto.TransactionPeriodResult;
import com.travelpay.ledger.application.transaction.dto.TransactionResult;
import com.travelpay.ledger.domain.LedgerErrorCode;
import com.travelpay.ledger.domain.transaction.DuplicateReferenceException;
import com.travelpay.ledger.domain.transaction.LedgerEntryLine;
import com.travelpay.ledger.domain.transaction.LedgerTransaction;
import com.travelpay.ledger.domain.transaction.LedgerTransactionPosition;
import com.travelpay.ledger.domain.transaction.LedgerTransactionRepository;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerTransactionService {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초) */
    private static final int TX_TIMEOUT_SECONDS = 3;
    /** 기간 목록 한 페이지 기본·최대 건수 */
    private static final int MAX_PAGE_SIZE = 100;
    /** from·to 공통 하한 (포함) */
    private static final Instant MIN_SUPPORTED_INSTANT = Instant.parse("0001-01-01T00:00:00Z");
    /** from 상한 (포함). 업무상 마지막 시각 */
    private static final Instant MAX_FROM_INSTANT = Instant.parse("9999-12-31T23:59:59.999999Z");
    /** to 상한 (포함). to는 미포함 경계라 from 상한보다 1µs 뒤까지 받아야 마지막 시각 거래를 조회할 수 있다 */
    private static final Instant MAX_TO_INSTANT = Instant.parse("+10000-01-01T00:00:00Z");

    private final LedgerTransactionRecorder recorder;
    private final LedgerTransactionRepository transactionRepository;

    /**
     * 거래를 기록한다. 트랜잭션을 열지 않는 진입점이라 reference UNIQUE 위반을 트랜잭션 밖에서 잡아
     * 새 트랜잭션에서 기존 거래를 다시 읽을 수 있다.
     * 거절(REJECTED)도 정상 결과로 돌려준다. 예외로 던지면 거절 기록까지 롤백되기 때문.
     */
    public TransactionResult register(RegisterTransactionCommand command) {
        Objects.requireNonNull(command, "command");
        TransactionCommandValidator.validate(command);
        String requestHash = LedgerRequestHasher.hash(command);
        try {
            return recorder.record(command, requestHash);
        } catch (DuplicateReferenceException e) {
            return recorder.resolveExisting(command, requestHash);
        }
    }

    /**
     * 같은 reference를 취소 기록(REJECTED, CANCELLED_BY_REQUESTER)으로 먼저 차지해, 늦게 도착한 등록이 반영되지 않게 한다.
     * 등록처럼 트랜잭션을 열지 않는 진입점이라 reference UNIQUE 위반을 밖에서 잡아 새 트랜잭션에서 먼저 저장된 거래를 읽는다.
     */
    public CancelTransactionResult cancel(CancelTransactionCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.referenceType() == null || command.referenceId() == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        String requestHash = LedgerRequestHasher.hashCancel(command.referenceType(), command.referenceId());
        try {
            return recorder.cancel(command, requestHash);
        } catch (DuplicateReferenceException e) {
            return recorder.existingForCancel(command.referenceType(), command.referenceId());
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public TransactionDetailResult getByReference(ReferenceType referenceType, UUID referenceId) {
        Objects.requireNonNull(referenceType, "referenceType");
        Objects.requireNonNull(referenceId, "referenceId");
        LedgerTransaction transaction = transactionRepository.findByReference(referenceType, referenceId)
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.LEDGER_TRANSACTION_NOT_FOUND));
        List<LedgerEntryResult> entries = transactionRepository.findEntryLines(transaction.getId()).stream()
                .map(LedgerEntryResult::from)
                .toList();
        return TransactionDetailResult.of(transaction, entries);
    }

    /**
     * 기간 [from, to) 거래 목록 (createdAt, id 오름차순).
     * from > to 검사는 원래 값으로 먼저 하고, 그 뒤 from·to를 다음 마이크로초로 올려 범위를 검사한다.
     * 커서 위치가 [from, to) 밖이면 조회 전에 거부한다 ({@link LedgerPeriodCursor#decode}).
     * size + 1건을 읽어 다음 페이지 여부를 정하고, 다음 커서는 돌려주는 마지막 행으로 만든다.
     * 항목은 페이지 거래 id를 모아 한 번에 읽는다 (거래가 없으면 읽지 않음).
     */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public TransactionPeriodResult getByPeriod(TransactionPeriodQuery query) {
        Objects.requireNonNull(query, "query");
        ReferenceType referenceType = Objects.requireNonNull(query.referenceType(), "referenceType");
        Instant rawFrom = Objects.requireNonNull(query.from(), "from");
        Instant rawTo = Objects.requireNonNull(query.to(), "to");
        if (rawFrom.isAfter(rawTo)) {
            throw new BusinessException(LedgerErrorCode.LEDGER_INVALID_PERIOD);
        }
        Instant from = ceilWithinSupported(rawFrom, MAX_FROM_INSTANT);
        Instant to = ceilWithinSupported(rawTo, MAX_TO_INSTANT);
        int size = pageSize(query.size());
        LedgerTransactionPosition after = null;
        if (query.cursor() != null) {
            LedgerPeriodCursor cursor = LedgerPeriodCursor.decode(query.cursor());
            if (!cursor.matches(referenceType, from, to)) {
                throw new BusinessException(LedgerErrorCode.LEDGER_CURSOR_MISMATCH);
            }
            after = cursor.position();
        }

        List<LedgerTransaction> rows = transactionRepository.findByPeriod(referenceType, from, to, after, size + 1);
        boolean hasNext = rows.size() > size;
        List<LedgerTransaction> page = hasNext ? rows.subList(0, size) : rows;

        Map<UUID, List<LedgerEntryLine>> lines = page.isEmpty() ? Map.of()
                : transactionRepository.findEntryLinesByTransactionIds(
                        page.stream().map(LedgerTransaction::getId).toList());
        List<TransactionDetailResult> items = page.stream()
                .map(transaction -> TransactionDetailResult.of(transaction,
                        lines.getOrDefault(transaction.getId(), List.of()).stream()
                                .map(LedgerEntryResult::from)
                                .toList()))
                .toList();

        String nextCursor = null;
        if (hasNext) {
            LedgerTransaction last = page.getLast();
            nextCursor = new LedgerPeriodCursor(referenceType, from, to,
                    new LedgerTransactionPosition(last.getCreatedAt(), last.getId())).encode();
        }
        return new TransactionPeriodResult(items, nextCursor);
    }

    /**
     * 다음 마이크로초로 올린 값이 [0001-01-01T00:00:00Z, max] 밖이면 400.
     * from의 max는 9999-12-31T23:59:59.999999Z, to의 max는 +10000-01-01T00:00:00Z.
     * 이 범위는 커서의 epoch 마이크로초(long)와 PostgreSQL timestamptz 한계 안에 있다.
     * max는 마이크로초 단위 값이라 "원래 값 > max"와 "올린 값 > max"가 같다. 그래서 상한은 올리기 전에 먼저 걸러
     * Instant 최댓값 근처를 올리다 넘치는 일을 막는다.
     */
    private static Instant ceilWithinSupported(Instant instant, Instant max) {
        if (instant.isAfter(max)) {
            throw new BusinessException(LedgerErrorCode.LEDGER_INVALID_PERIOD);
        }
        Instant ceiled = LedgerPeriodCursor.ceilToMicros(instant);
        if (ceiled.isBefore(MIN_SUPPORTED_INSTANT)) {
            throw new BusinessException(LedgerErrorCode.LEDGER_INVALID_PERIOD);
        }
        return ceiled;
    }

    private static int pageSize(Integer requested) {
        if (requested == null) {
            return MAX_PAGE_SIZE;
        }
        if (requested < 1) {
            throw new BusinessException(LedgerErrorCode.LEDGER_INVALID_PAGE_SIZE);
        }
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
