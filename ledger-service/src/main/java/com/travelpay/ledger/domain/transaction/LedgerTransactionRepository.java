package com.travelpay.ledger.domain.transaction;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface LedgerTransactionRepository {

    Optional<LedgerTransaction> findByReference(ReferenceType referenceType, UUID referenceId);

    boolean existsById(UUID transactionId);

    /** 즉시 반영(flush)한다. 같은 reference가 이미 있으면 DuplicateReferenceException */
    LedgerTransaction save(LedgerTransaction transaction);

    void saveEntries(List<LedgerEntry> entries);

    /** 항목과 계정을 한 번에 읽는다. 순서는 항목 id 순 */
    List<LedgerEntryLine> findEntryLines(UUID transactionId);

    /**
     * 유형이 같고 created_at이 [from, to)인 거래를 (createdAt, id) 오름차순으로 최대 limit건.
     * after가 있으면 그 위치보다 뒤의 행만 읽는다.
     */
    List<LedgerTransaction> findByPeriod(ReferenceType referenceType, Instant from, Instant to,
                                         LedgerTransactionPosition after, int limit);

    /** 여러 거래의 항목을 한 번에 읽어 거래 id별로 묶는다. 거래별 항목 순서는 항목 id 순. 항목 없는 거래는 키 없음 */
    Map<UUID, List<LedgerEntryLine>> findEntryLinesByTransactionIds(Collection<UUID> transactionIds);
}
