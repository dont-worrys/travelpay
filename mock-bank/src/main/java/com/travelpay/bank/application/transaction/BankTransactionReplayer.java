package com.travelpay.bank.application.transaction;

import com.travelpay.bank.application.transaction.dto.BankRefundCommand;
import com.travelpay.bank.application.transaction.dto.BankTransactionResult;
import com.travelpay.bank.application.transaction.dto.BankWithdrawalCommand;
import com.travelpay.bank.domain.account.BankAccount;
import com.travelpay.bank.domain.account.BankAccountOwnerType;
import com.travelpay.bank.domain.account.BankAccountRepository;
import com.travelpay.bank.domain.transaction.BankTransaction;
import com.travelpay.bank.domain.transaction.BankTransactionRepository;
import com.travelpay.bank.domain.transaction.BankTransactionType;
import com.travelpay.common.error.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 같은 requestId의 기존 기록을 찾아 그대로 돌려준다. 유형이나 내용이 다르면 409.
 * 내용 비교는 거래 행에 저장된 값 기준이다.
 * - 출금: 금액 + 요청 계좌(USER)의 id == from_account_id (계좌 없음 거절이면 둘 다 null). 예금주는 저장하지 않아 비교하지 않는다
 * - 환불: 금액 + 요청 원거래 id == original_tx_id (원거래 없음 거절이면 둘 다 null)
 */
@Component
@RequiredArgsConstructor
public class BankTransactionReplayer {

    private final BankAccountRepository bankAccountRepository;
    private final BankTransactionRepository bankTransactionRepository;

    public Optional<BankTransactionResult> findDebit(BankWithdrawalCommand command) {
        return find(command.requestId(), BankTransactionType.DEBIT, transaction ->
                transaction.hasSameDebit(findUserAccount(command).map(BankAccount::getId).orElse(null),
                        command.amount()));
    }

    public Optional<BankTransactionResult> findRefund(BankRefundCommand command) {
        return find(command.requestId(), BankTransactionType.REFUND, transaction ->
                transaction.hasSameRefund(existingOriginalTxId(command), command.amount()));
    }

    /** 유니크 위반 직후 호출: 먼저 커밋된 기록이 반드시 있다. */
    public BankTransactionResult replayDebit(BankWithdrawalCommand command) {
        return findDebit(command).orElseThrow(BankTransactionReplayer::missingAfterDuplicate);
    }

    /** 유니크 위반 직후 호출: 먼저 커밋된 기록이 반드시 있다. */
    public BankTransactionResult replayRefund(BankRefundCommand command) {
        return findRefund(command).orElseThrow(BankTransactionReplayer::missingAfterDuplicate);
    }

    /** 출금 대상 사용자 계좌. 플랫폼 계좌는 출금 대상이 아니므로 없는 것으로 본다 */
    public Optional<BankAccount> findUserAccount(BankWithdrawalCommand command) {
        return bankAccountRepository.findByBankCodeAndAccountNumber(command.bankCode(), command.accountNumber())
                .filter(account -> account.getOwnerType() == BankAccountOwnerType.USER);
    }

    private UUID existingOriginalTxId(BankRefundCommand command) {
        return bankTransactionRepository.findById(command.originalTxId()).map(BankTransaction::getId).orElse(null);
    }

    private Optional<BankTransactionResult> find(String requestId, BankTransactionType expectedType,
                                                 Predicate<BankTransaction> sameContent) {
        return bankTransactionRepository.findByRequestId(requestId)
                .map(transaction -> {
                    if (transaction.getType() != expectedType || !sameContent.test(transaction)) {
                        throw new BusinessException(BankErrorCode.REQUEST_ID_CONFLICT);
                    }
                    return BankTransactionResult.replayed(transaction);
                });
    }

    private static IllegalStateException missingAfterDuplicate() {
        return new IllegalStateException("중복 requestId 위반 후 기존 거래를 찾을 수 없습니다.");
    }
}
