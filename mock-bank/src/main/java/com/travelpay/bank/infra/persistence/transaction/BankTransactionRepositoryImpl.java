package com.travelpay.bank.infra.persistence.transaction;

import com.travelpay.bank.domain.transaction.BankDuplicateRefundException;
import com.travelpay.bank.domain.transaction.BankDuplicateRequestException;
import com.travelpay.bank.domain.transaction.BankTransaction;
import com.travelpay.bank.domain.transaction.BankTransactionRepository;
import com.travelpay.bank.domain.transaction.BankTransactionStatus;
import com.travelpay.bank.domain.transaction.BankTransactionType;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class BankTransactionRepositoryImpl implements BankTransactionRepository {

    private static final String REQUEST_UNIQUE = "uk_bank_transactions_request";
    private static final String REFUND_ONCE_UNIQUE = "uk_bank_transactions_refund_once";

    private final BankTransactionJpaRepository bankTransactionJpaRepository;

    @Override
    public BankTransaction saveAndFlush(BankTransaction transaction) {
        try {
            return bankTransactionJpaRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException e) {
            String constraint = constraintName(e);
            if (constraint.contains(REFUND_ONCE_UNIQUE)) {
                throw new BankDuplicateRefundException(e);
            }
            if (constraint.contains(REQUEST_UNIQUE)) {
                throw new BankDuplicateRequestException(e);
            }
            throw e;
        }
    }

    @Override
    public Optional<BankTransaction> findByRequestId(String requestId) {
        return bankTransactionJpaRepository.findByRequestId(requestId);
    }

    @Override
    public Optional<BankTransaction> findById(UUID id) {
        return bankTransactionJpaRepository.findById(id);
    }

    @Override
    public boolean existsSucceededRefund(UUID originalTxId) {
        return bankTransactionJpaRepository.existsByOriginalTxIdAndTypeAndStatus(
                originalTxId, BankTransactionType.REFUND, BankTransactionStatus.SUCCEEDED);
    }

    /** Hibernate가 뽑은 제약 이름을 우선 쓰고, 없으면 원인 메시지에서 찾는다. */
    private static String constraintName(DataIntegrityViolationException e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof ConstraintViolationException violation && violation.getConstraintName() != null) {
                return violation.getConstraintName().toLowerCase(Locale.ROOT);
            }
        }
        String message = e.getMostSpecificCause().getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }
}
