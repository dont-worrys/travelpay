package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankRefundCommand;
import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.domain.account.BankAccountRepository;
import com.travelpay.bank.domain.transaction.BankDuplicateRefundException;
import com.travelpay.bank.domain.transaction.BankFailureCode;
import com.travelpay.bank.domain.transaction.BankTransaction;
import com.travelpay.bank.domain.transaction.BankTransactionRepository;
import com.travelpay.bank.domain.transaction.BankTransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 환불 한 건을 한 트랜잭션에서 처리한다. 플랫폼 계좌(원출금의 to) → 원출금 계좌(원출금의 from).
 * 잔액 변경 순서는 사용자 → 플랫폼으로 출금과 같게 맞춰 교착을 막는다.
 */
@Component
@RequiredArgsConstructor
public class BankRefundTransaction {

    private final BankAccountRepository bankAccountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final BankTransactionReplayer replayer;

    @Transactional
    public BankTransactionResult refund(BankRefundCommand command) {
        Optional<BankTransactionResult> existing = replayer.find(command.requestId(), BankTransactionType.REFUND);
        if (existing.isPresent()) {
            return existing.get();
        }

        Optional<BankTransaction> found = bankTransactionRepository.findById(command.originalTxId());
        if (found.isEmpty()) {
            return save(BankTransaction.failedRefund(command.requestId(), null, null, command.amount(),
                    null, BankFailureCode.ORIGINAL_TX_NOT_FOUND));
        }

        BankTransaction original = found.get();
        if (!original.isRefundable()) {
            return fail(command, original, BankFailureCode.ORIGINAL_TX_NOT_REFUNDABLE);
        }
        if (original.getAmount() != command.amount()) {
            return fail(command, original, BankFailureCode.AMOUNT_MISMATCH);
        }
        if (bankTransactionRepository.existsSucceededRefund(original.getId())) {
            return fail(command, original, BankFailureCode.ALREADY_REFUNDED);
        }

        bankAccountRepository.increaseBalance(original.getFromAccountId(), command.amount());
        if (bankAccountRepository.decreaseBalance(original.getToAccountId(), command.amount()) == 0) {
            // 동시 환불이 사용자 계좌 잠금을 먼저 잡고 커밋했으면 플랫폼 잔액이 이미 빠져 있다
            if (bankTransactionRepository.existsSucceededRefund(original.getId())) {
                throw new BankDuplicateRefundException();
            }
            throw new IllegalStateException("플랫폼 계좌 잔액이 환불 금액보다 적습니다.");
        }

        return save(BankTransaction.succeededRefund(command.requestId(), original.getToAccountId(),
                original.getFromAccountId(), command.amount(), original.getId()));
    }

    private BankTransactionResult fail(BankRefundCommand command, BankTransaction original,
                                       BankFailureCode failureCode) {
        return save(BankTransaction.failedRefund(command.requestId(), original.getToAccountId(),
                original.getFromAccountId(), command.amount(), original.getId(), failureCode));
    }

    private BankTransactionResult save(BankTransaction transaction) {
        return BankTransactionResult.created(bankTransactionRepository.saveAndFlush(transaction));
    }
}
