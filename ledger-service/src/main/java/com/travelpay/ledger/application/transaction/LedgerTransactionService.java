package com.travelpay.ledger.application.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.application.transaction.dto.TransactionResult;
import com.travelpay.ledger.domain.LedgerErrorCode;
import com.travelpay.ledger.domain.transaction.DuplicateReferenceException;
import com.travelpay.ledger.domain.transaction.LedgerTransactionRepository;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LedgerTransactionService {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초) */
    private static final int TX_TIMEOUT_SECONDS = 3;

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

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public TransactionResult getByReference(ReferenceType referenceType, UUID referenceId) {
        Objects.requireNonNull(referenceType, "referenceType");
        Objects.requireNonNull(referenceId, "referenceId");
        return transactionRepository.findByReference(referenceType, referenceId)
                .map(transaction -> TransactionResult.of(transaction, false))
                .orElseThrow(() -> new BusinessException(LedgerErrorCode.LEDGER_TRANSACTION_NOT_FOUND));
    }
}
