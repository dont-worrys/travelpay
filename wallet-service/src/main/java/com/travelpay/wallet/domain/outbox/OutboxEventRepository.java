package com.travelpay.wallet.domain.outbox;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository {

    void save(OutboxEvent event);

    /**
     * 미발행 건을 재시도가 적은 순 → 오래된 순으로 잠가 가져온다. 다른 발행기가 잠근 행은 건너뛴다.
     * 반복 실패 건이 쌓여도 신규 건이 밀리지 않게 한다.
     */
    List<OutboxEvent> findPendingForUpdate(int limit);

    /** ids(비어 있지 않음) 중 PENDING인 행의 retry_count를 1 올린다. 다른 트랜잭션이 잠근 행은 건너뛴다. @return 변경 건수 */
    int increaseRetryCountIfPending(Collection<UUID> ids);
}
