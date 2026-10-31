package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankRefundCommand;
import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.domain.transaction.BankFailureCode;
import com.travelpay.bank.domain.transaction.BankTransaction;
import com.travelpay.bank.domain.transaction.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 롤백된 환불 트랜잭션과 분리해 FAILED 기록을 새 트랜잭션으로 남긴다. */
@Component
@RequiredArgsConstructor
public class BankTransactionRecorder {

    private final BankTransactionRepository bankTransactionRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BankTransactionResult recordAlreadyRefunded(BankRefundCommand command) {
        BankTransaction original = bankTransactionRepository.findById(command.originalTxId())
                .orElseThrow(() -> new IllegalStateException("환불 원거래를 찾을 수 없습니다."));
        BankTransaction saved = bankTransactionRepository.saveAndFlush(BankTransaction.failedRefund(
                command.requestId(), original.getToAccountId(), original.getFromAccountId(),
                command.amount(), original.getId(), BankFailureCode.ALREADY_REFUNDED));
        return BankTransactionResult.created(saved);
    }
}
