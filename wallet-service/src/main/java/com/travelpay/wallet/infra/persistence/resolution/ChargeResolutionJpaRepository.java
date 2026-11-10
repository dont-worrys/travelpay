package com.travelpay.wallet.infra.persistence.resolution;

import com.travelpay.wallet.domain.resolution.ChargeResolution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ChargeResolutionJpaRepository extends JpaRepository<ChargeResolution, UUID> {

    Optional<ChargeResolution> findByIdempotencyKey(UUID idempotencyKey);
}
