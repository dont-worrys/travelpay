package com.travelpay.wallet.infra.persistence.charge;

import com.travelpay.wallet.domain.charge.ChargeStatusHistory;
import com.travelpay.wallet.domain.charge.ChargeStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ChargeStatusHistoryRepositoryImpl implements ChargeStatusHistoryRepository {

    private final ChargeStatusHistoryJpaRepository jpaRepository;

    @Override
    public void save(ChargeStatusHistory history) {
        jpaRepository.save(history);
    }
}
