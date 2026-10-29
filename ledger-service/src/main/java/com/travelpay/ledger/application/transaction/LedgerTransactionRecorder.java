package com.travelpay.ledger.application.transaction;

import com.travelpay.common.error.BusinessException;
import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;
import com.travelpay.ledger.application.transaction.dto.TransactionResult;
import com.travelpay.ledger.domain.LedgerErrorCode;
import com.travelpay.ledger.domain.account.LedgerAccount;
import com.travelpay.ledger.domain.account.LedgerAccountRepository;
import com.travelpay.ledger.domain.account.OwnerType;
import com.travelpay.ledger.domain.transaction.Direction;
import com.travelpay.ledger.domain.transaction.LedgerEntry;
import com.travelpay.ledger.domain.transaction.LedgerTransaction;
import com.travelpay.ledger.domain.transaction.LedgerTransactionRepository;
import com.travelpay.ledger.domain.transaction.RejectReason;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 거래 한 건을 한 트랜잭션으로 기록한다. 거래·항목·계정 잔액이 함께 커밋되거나 함께 롤백된다.
 *
 * READ_COMMITTED 전제: FOR UPDATE는 다른 트랜잭션이 커밋한 뒤의 최신 행을 잠그므로
 * 잠금 후에 읽은 상태·잔액으로 판단하면 정확하다. REPEATABLE READ로 올리면 그 사이 바뀐 행을 잠글 때
 * 직렬화 오류로 롤백된다.
 *
 * 같은 reference 동시 요청은 여기서 처리하지 않는다. 거래 INSERT의 UNIQUE 위반(DuplicateReferenceException)으로
 * 이 트랜잭션은 롤백되고, LedgerTransactionService가 새 트랜잭션에서 기존 거래를 다시 읽는다.
 */
@Component
@RequiredArgsConstructor
class LedgerTransactionRecorder {

    /** DB가 느릴 때 호출자를 붙잡아 두는 상한(초) */
    private static final int TX_TIMEOUT_SECONDS = 3;

    private final LedgerTransactionRepository transactionRepository;
    private final LedgerAccountRepository accountRepository;

    @Transactional(isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public TransactionResult record(RegisterTransactionCommand command, String requestHash) {
        Optional<LedgerTransaction> existing =
                transactionRepository.findByReference(command.referenceType(), command.referenceId());
        if (existing.isPresent()) {
            return toExistingResult(existing.get(), requestHash);
        }

        if (command.originalTransactionId() != null
                && !transactionRepository.existsById(command.originalTransactionId())) {
            throw new BusinessException(LedgerErrorCode.LEDGER_ORIGINAL_TRANSACTION_NOT_FOUND);
        }
        // TODO: referenceType별 추가 검증(예: 결제 환불의 원거래 상태·중복·금액)은 유형별 정책 클래스로 해당 도메인 담당이 추가 예정

        // 잠금 전에는 ID만 찾는다. 엔티티를 먼저 읽어 두면 아래 FOR UPDATE 조회가 이미 올라온 인스턴스를
        // 그대로 돌려줘 잠금 전에 읽은 잔액으로 판단하게 된다.
        List<UUID> accountIds = new ArrayList<>(command.entries().size());
        for (RegisterTransactionCommand.Entry entry : command.entries()) {
            Optional<UUID> accountId = findAccountId(entry);
            if (accountId.isEmpty()) {
                return reject(command, requestHash, RejectReason.ACCOUNT_NOT_FOUND);
            }
            accountIds.add(accountId.get());
        }

        // 데드락 방지: 모든 거래가 계정 ID 순으로 잠근다.
        // 핫 계정: CHARGE_SOURCE는 음수 허용이라 잔액 확인이 필요 없지만 잠금 순서 일관성을 위해 같은 규칙으로 잠근다.
        // 모든 충전이 몰리는 계정이라 성능 측정 후 개선 대상.
        Set<UUID> distinctIds = new LinkedHashSet<>(accountIds);
        Map<UUID, LedgerAccount> accounts = accountRepository.lockAllInIdOrder(distinctIds)
                .stream()
                .collect(Collectors.toMap(LedgerAccount::getId, Function.identity()));
        if (accounts.size() != distinctIds.size()) {
            return reject(command, requestHash, RejectReason.ACCOUNT_NOT_FOUND);
        }

        Optional<RejectReason> rejectReason = judge(command, accountIds, accounts);
        if (rejectReason.isPresent()) {
            return reject(command, requestHash, rejectReason.get());
        }

        LedgerTransaction transaction = transactionRepository.save(LedgerTransaction.posted(
                command.referenceType(), command.referenceId(), requestHash,
                command.userId(), command.originalTransactionId(), command.description()));
        List<LedgerEntry> entries = new ArrayList<>(command.entries().size());
        for (int i = 0; i < command.entries().size(); i++) {
            RegisterTransactionCommand.Entry entry = command.entries().get(i);
            LedgerAccount account = accounts.get(accountIds.get(i));
            long balanceAfter = account.apply(entry.direction(), entry.amount());
            entries.add(LedgerEntry.create(transaction.getId(), account.getId(), entry.currency(),
                    entry.entryType(), entry.direction(), entry.amount(), balanceAfter));
        }
        transactionRepository.saveEntries(entries);
        return TransactionResult.of(transaction, true);
    }

    /** 동시 요청으로 먼저 저장된 거래를 새 트랜잭션에서 다시 읽어 같은 규칙으로 응답한다 */
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED, timeout = TX_TIMEOUT_SECONDS)
    public TransactionResult resolveExisting(RegisterTransactionCommand command, String requestHash) {
        LedgerTransaction existing = transactionRepository
                .findByReference(command.referenceType(), command.referenceId())
                .orElseThrow(() -> new IllegalStateException("중복으로 거절된 거래를 다시 찾을 수 없습니다."));
        return toExistingResult(existing, requestHash);
    }

    private Optional<UUID> findAccountId(RegisterTransactionCommand.Entry entry) {
        return entry.ownerType() == OwnerType.USER
                ? accountRepository.findUserWalletAccountId(entry.walletId(), entry.currency())
                : accountRepository.findSystemAccountId(entry.accountType(), entry.currency());
    }

    /**
     * 잠금 후의 값으로 상태·잔액을 판단한다. 엔티티는 바꾸지 않는다(거절 시 변경이 커밋되지 않도록).
     * 같은 계정이 여러 항목에 나와도 맞도록 요청 순서대로 누적 잔액을 계산하고,
     * USER 계정 DEBIT은 LedgerAccount.canApply와 같은 규칙(잔액 >= 금액)으로 확인한다. SYSTEM 계정은 음수 허용.
     */
    private Optional<RejectReason> judge(RegisterTransactionCommand command, List<UUID> accountIds,
                                         Map<UUID, LedgerAccount> accounts) {
        if (accounts.values().stream().anyMatch(account -> !account.isActive())) {
            return Optional.of(RejectReason.WALLET_SUSPENDED);
        }
        Map<UUID, Long> runningBalances = new HashMap<>();
        for (int i = 0; i < command.entries().size(); i++) {
            RegisterTransactionCommand.Entry entry = command.entries().get(i);
            LedgerAccount account = accounts.get(accountIds.get(i));
            long balance = runningBalances.getOrDefault(account.getId(), account.getBalance());
            if (account.isUserAccount() && entry.direction() == Direction.DEBIT && balance < entry.amount()) {
                return Optional.of(RejectReason.INSUFFICIENT_BALANCE);
            }
            long next = entry.direction() == Direction.CREDIT
                    ? Math.addExact(balance, entry.amount())
                    : Math.subtractExact(balance, entry.amount());
            runningBalances.put(account.getId(), next);
        }
        return Optional.empty();
    }

    /** 확정 거절은 REJECTED 거래만 남기고 항목은 없다 */
    private TransactionResult reject(RegisterTransactionCommand command, String requestHash, RejectReason reason) {
        LedgerTransaction transaction = transactionRepository.save(LedgerTransaction.rejected(
                command.referenceType(), command.referenceId(), requestHash,
                command.userId(), reason, command.description()));
        return TransactionResult.of(transaction, true);
    }

    private static TransactionResult toExistingResult(LedgerTransaction existing, String requestHash) {
        if (!existing.hasSameRequest(requestHash)) {
            throw new BusinessException(LedgerErrorCode.LEDGER_REFERENCE_CONFLICT);
        }
        return TransactionResult.of(existing, false);
    }
}
