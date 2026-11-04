package com.travelpay.bank.domain.transaction;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BankTransactionRepository {

    /**
     * 즉시 insert해 제약 위반을 이 자리에서 드러낸다.
     * @throws BankDuplicateRequestException 같은 requestId 기록이 이미 있을 때
     * @throws BankDuplicateRefundException 원거래에 성공한 환불이 이미 있을 때
     */
    BankTransaction saveAndFlush(BankTransaction transaction);

    Optional<BankTransaction> findByRequestId(String requestId);

    Optional<BankTransaction> findById(UUID id);

    boolean existsSucceededRefund(UUID originalTxId);

    /** created_at이 [from, to) 범위인 거래 */
    Page<BankTransaction> findCreatedBetween(Instant from, Instant to, Pageable pageable);
}
