package com.travelpay.wallet.domain.outbox;

import java.util.List;

public interface OutboxEventRepository {

    void save(OutboxEvent event);

    /** 미발행 건을 오래된 순으로 잠가 가져온다. 다른 발행기가 잠근 행은 건너뛴다 */
    List<OutboxEvent> findPendingForUpdate(int limit);
}
