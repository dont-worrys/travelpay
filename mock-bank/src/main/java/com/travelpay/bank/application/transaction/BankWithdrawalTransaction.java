package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.application.transaction.dto.BankWithdrawalCommand;
import com.travelpay.bank.domain.account.BankAccount;
import com.travelpay.bank.domain.account.BankAccountOwnerType;
import com.travelpay.bank.domain.account.BankAccountRepository;
import com.travelpay.bank.domain.transaction.BankFailureCode;
import com.travelpay.bank.domain.transaction.BankTransaction;
import com.travelpay.bank.domain.transaction.BankTransactionRepository;
import com.travelpay.bank.domain.transaction.BankTransactionType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 출금 한 건을 한 트랜잭션에서 처리한다.
 * 잔액 변경 순서는 사용자 → 플랫폼으로 환불과 같게 맞춰 교착을 막는다.
 * 기록 저장이 requestId 중복으로 실패하면 잔액 변경까지 함께 롤백된다.
 */
@Component
@RequiredArgsConstructor
public class BankWithdrawalTransaction {

    private final BankAccountRepository bankAccountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final BankTransactionReplayer replayer;

    @Transactional
    public BankTransactionResult withdraw(BankWithdrawalCommand command) {
        Optional<BankTransactionResult> existing = replayer.find(command.requestId(), BankTransactionType.DEBIT);
        if (existing.isPresent()) {
            return existing.get();
        }

        BankAccount platform = bankAccountRepository.findPlatformAccount()
                .orElseThrow(() -> new IllegalStateException("플랫폼 계좌가 없습니다."));
        Optional<BankAccount> found = bankAccountRepository
                .findByBankCodeAndAccountNumber(command.bankCode(), command.accountNumber())
                .filter(account -> account.getOwnerType() == BankAccountOwnerType.USER);
        if (found.isEmpty()) {
            return fail(command, null, platform.getId(), BankFailureCode.ACCOUNT_NOT_FOUND);
        }

        BankAccount account = found.get();
        if (!account.isActive()) {
            return fail(command, account.getId(), platform.getId(), BankFailureCode.ACCOUNT_SUSPENDED);
        }
        if (!account.holderMatches(command.holderName().strip())) {
            return fail(command, account.getId(), platform.getId(), BankFailureCode.HOLDER_MISMATCH);
        }
        if (bankAccountRepository.decreaseBalance(account.getId(), command.amount()) == 0) {
            return fail(command, account.getId(), platform.getId(), BankFailureCode.INSUFFICIENT_BALANCE);
        }
        bankAccountRepository.increaseBalance(platform.getId(), command.amount());

        BankTransaction saved = bankTransactionRepository.saveAndFlush(BankTransaction.succeededDebit(
                command.requestId(), account.getId(), platform.getId(), command.amount()));
        return BankTransactionResult.created(saved);
    }

    private BankTransactionResult fail(BankWithdrawalCommand command, UUID fromAccountId, UUID toAccountId,
                                       BankFailureCode failureCode) {
        BankTransaction saved = bankTransactionRepository.saveAndFlush(BankTransaction.failedDebit(
                command.requestId(), fromAccountId, toAccountId, command.amount(), failureCode));
        return BankTransactionResult.created(saved);
    }
}
