package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.application.transaction.dto.BankWithdrawalCommand;
import com.travelpay.bank.domain.transaction.BankDuplicateRequestException;
import com.travelpay.bank.domain.transaction.BankTransactionType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 트랜잭션 밖 래퍼: 같은 requestId가 동시에 저장되면 롤백 후 먼저 커밋된 기록을 다시 읽는다. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankWithdrawalService {

    private final BankWithdrawalTransaction withdrawalTransaction;
    private final BankTransactionReplayer replayer;

    public BankTransactionResult withdraw(BankWithdrawalCommand command) {
        BankTransactionResult result;
        try {
            result = withdrawalTransaction.withdraw(command);
        } catch (BankDuplicateRequestException e) {
            result = replayer.replay(command.requestId(), BankTransactionType.DEBIT);
        }
        log.info("출금 처리 requestId={}, status={}, failureCode={}, created={}",
                result.requestId(), result.status(), result.failureCode(), result.created());
        return result;
    }
}
