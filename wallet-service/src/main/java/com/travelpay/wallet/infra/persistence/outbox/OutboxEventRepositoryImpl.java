package com.travelpay.wallet.infra.persistence.outbox;

import com.travelpay.wallet.domain.outbox.OutboxEvent;
import com.travelpay.wallet.domain.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventJpaRepository jpaRepository;

    @Override
    public void save(OutboxEvent event) {
        jpaRepository.save(event);
    }

    @Override
    public List<OutboxEvent> findPendingForUpdate(int limit) {
        return jpaRepository.findPendingForUpdate(limit);
    }
}
