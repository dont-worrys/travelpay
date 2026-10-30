package com.travelpay.wallet.infra.persistence.outbox;

import com.travelpay.wallet.domain.outbox.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, UUID> {
}
