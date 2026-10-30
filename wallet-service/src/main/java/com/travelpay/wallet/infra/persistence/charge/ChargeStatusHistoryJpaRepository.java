package com.travelpay.wallet.infra.persistence.charge;

import com.travelpay.wallet.domain.charge.ChargeStatusHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChargeStatusHistoryJpaRepository extends JpaRepository<ChargeStatusHistory, UUID> {
}
