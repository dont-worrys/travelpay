package com.travelpay.ledger.infra.persistence.transaction;

import com.travelpay.ledger.domain.transaction.DuplicateReferenceException;
import com.travelpay.ledger.domain.transaction.LedgerEntry;
import com.travelpay.ledger.domain.transaction.LedgerEntryLine;
import com.travelpay.ledger.domain.transaction.LedgerTransaction;
import com.travelpay.ledger.domain.transaction.LedgerTransactionPosition;
import com.travelpay.ledger.domain.transaction.LedgerTransactionRepository;
import com.travelpay.ledger.domain.transaction.ReferenceType;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LedgerTransactionRepositoryImpl implements LedgerTransactionRepository {

    private static final String REFERENCE_CONSTRAINT = "uk_ledger_transactions_reference";

    private final LedgerTransactionJpaRepository transactionJpaRepository;
    private final LedgerEntryJpaRepository entryJpaRepository;

    @Override
    public Optional<LedgerTransaction> findByReference(ReferenceType referenceType, UUID referenceId) {
        return transactionJpaRepository.findByReferenceTypeAndReferenceId(referenceType, referenceId);
    }

    @Override
    public boolean existsById(UUID transactionId) {
        return transactionJpaRepository.existsById(transactionId);
    }

    /** flush로 reference UNIQUE 위반을 이 자리에서 드러낸다. 다른 무결성 위반은 그대로 던진다 */
    @Override
    public LedgerTransaction save(LedgerTransaction transaction) {
        try {
            return transactionJpaRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException e) {
            if (isReferenceViolation(e)) {
                throw new DuplicateReferenceException(e);
            }
            throw e;
        }
    }

    @Override
    public void saveEntries(List<LedgerEntry> entries) {
        entryJpaRepository.saveAll(entries);
    }

    @Override
    public List<LedgerEntryLine> findEntryLines(UUID transactionId) {
        return entryJpaRepository.findLinesByTransactionId(transactionId);
    }

    @Override
    public List<LedgerTransaction> findByPeriod(ReferenceType referenceType, Instant from, Instant to,
                                                LedgerTransactionPosition after, int limit) {
        if (after == null) {
            return transactionJpaRepository.findPeriodFirstPage(referenceType, from, to, Limit.of(limit));
        }
        return transactionJpaRepository.findPeriodPageAfter(referenceType, from, to,
                after.createdAt(), after.id(), Limit.of(limit));
    }

    @Override
    public Map<UUID, List<LedgerEntryLine>> findEntryLinesByTransactionIds(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<LedgerEntryLine>> lines = new HashMap<>();
        for (TransactionEntryLineRow row : entryJpaRepository.findLinesByTransactionIds(transactionIds)) {
            lines.computeIfAbsent(row.transactionId(), id -> new ArrayList<>()).add(row.toLine());
        }
        return lines;
    }

    private static boolean isReferenceViolation(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation) {
                return REFERENCE_CONSTRAINT.equals(violation.getConstraintName());
            }
        }
        return false;
    }
}
