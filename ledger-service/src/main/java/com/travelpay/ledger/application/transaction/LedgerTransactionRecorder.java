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
import java.util.Comparator;
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

        List<ResolvedEntry> resolvedEntries = inApplyOrder(command, accountIds);
        Map<UUID, Long> netChanges = netChanges(resolvedEntries);
        Optional<RejectReason> rejectReason = judge(accounts, netChanges);
        if (rejectReason.isPresent()) {
            return reject(command, requestHash, rejectReason.get());
        }

        Map<UUID, Long> lockedBalances = accounts.values().stream()
                .collect(Collectors.toMap(LedgerAccount::getId, LedgerAccount::getBalance));
        LedgerTransaction transaction = transactionRepository.save(LedgerTransaction.posted(
                command.referenceType(), command.referenceId(), requestHash,
                command.userId(), command.originalTransactionId(), command.description()));
        List<LedgerEntry> entries = new ArrayList<>(resolvedEntries.size());
        for (ResolvedEntry resolved : resolvedEntries) {
            RegisterTransactionCommand.Entry entry = resolved.entry();
            LedgerAccount account = accounts.get(resolved.accountId());
            long balanceAfter = account.apply(entry.direction(), entry.amount());
            entries.add(LedgerEntry.create(transaction.getId(), account.getId(), entry.currency(),
                    entry.entryType(), entry.direction(), entry.amount(), balanceAfter));
        }
        verifyFinalBalances(accounts, lockedBalances, netChanges);
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
     * 항목을 반영 순서로 정렬한다. 요청 해시가 항목 순서를 무시하므로 반영도 요청 순서와 무관해야 한다.
     * 계정 ID 순(잠금 순서와 같음), 같은 계정 안에서는 CREDIT 먼저: 순변동이 0 이상이면
     * 중간 잔액이 음수가 되지 않아 USER 계정 apply가 실패하지 않는다.
     * 같은 계정·방향 항목이 여럿이면 금액, entryType 순으로 정해 balance_after도 요청 순서와 무관하게 한다.
     */
    private static List<ResolvedEntry> inApplyOrder(RegisterTransactionCommand command, List<UUID> accountIds) {
        List<ResolvedEntry> resolved = new ArrayList<>(command.entries().size());
        for (int i = 0; i < command.entries().size(); i++) {
            resolved.add(new ResolvedEntry(accountIds.get(i), command.entries().get(i)));
        }
        resolved.sort(Comparator.comparing(ResolvedEntry::accountId)
                .thenComparing(resolvedEntry -> resolvedEntry.entry().direction() == Direction.CREDIT ? 0 : 1)
                .thenComparingLong(resolvedEntry -> resolvedEntry.entry().amount())
                .thenComparing(resolvedEntry -> resolvedEntry.entry().entryType()));
        return resolved;
    }

    /** 계정별 순변동(CREDIT 합 - DEBIT 합) */
    private static Map<UUID, Long> netChanges(List<ResolvedEntry> resolvedEntries) {
        Map<UUID, Long> netChanges = new HashMap<>();
        for (ResolvedEntry resolved : resolvedEntries) {
            RegisterTransactionCommand.Entry entry = resolved.entry();
            long signed = entry.direction() == Direction.CREDIT ? entry.amount() : Math.negateExact(entry.amount());
            netChanges.merge(resolved.accountId(), signed, Math::addExact);
        }
        return netChanges;
    }

    /**
     * 잠금 후의 값으로 상태·잔액을 판단한다. 엔티티는 바꾸지 않는다(거절 시 변경이 커밋되지 않도록).
     * 항목 순서와 무관하게 계정별 순변동으로 판단한다: USER 계정은 현재 잔액 + 순변동이 음수면 거절.
     * SYSTEM 계정은 음수 허용.
     */
    private static Optional<RejectReason> judge(Map<UUID, LedgerAccount> accounts, Map<UUID, Long> netChanges) {
        if (accounts.values().stream().anyMatch(account -> !account.isActive())) {
            return Optional.of(RejectReason.WALLET_SUSPENDED);
        }
        for (Map.Entry<UUID, Long> netChange : netChanges.entrySet()) {
            LedgerAccount account = accounts.get(netChange.getKey());
            if (account.isUserAccount() && Math.addExact(account.getBalance(), netChange.getValue()) < 0) {
                return Optional.of(RejectReason.INSUFFICIENT_BALANCE);
            }
        }
        return Optional.empty();
    }

    /** 반영 후 잔액이 잠금 시점 잔액 + 순변동과 다르면 예외로 트랜잭션 전체를 롤백한다 */
    private static void verifyFinalBalances(Map<UUID, LedgerAccount> accounts, Map<UUID, Long> lockedBalances,
                                            Map<UUID, Long> netChanges) {
        for (Map.Entry<UUID, Long> netChange : netChanges.entrySet()) {
            UUID accountId = netChange.getKey();
            long expected = Math.addExact(lockedBalances.get(accountId), netChange.getValue());
            long actual = accounts.get(accountId).getBalance();
            if (actual != expected) {
                throw new IllegalStateException(
                        "반영 후 잔액이 예상과 다릅니다: " + accountId + " expected=" + expected + " actual=" + actual);
            }
        }
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

    /** 요청 항목과 그 항목이 가리키는 계정 ID */
    private record ResolvedEntry(UUID accountId, RegisterTransactionCommand.Entry entry) {
    }
}
