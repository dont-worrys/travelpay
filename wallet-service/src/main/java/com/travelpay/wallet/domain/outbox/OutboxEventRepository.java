package com.travelpay.wallet.domain.outbox;

public interface OutboxEventRepository {

    void save(OutboxEvent event);
}
