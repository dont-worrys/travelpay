package com.travelpay.payment.infra.persistence.payment;

import com.travelpay.payment.domain.payment.Merchant;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MerchantJpaRepository extends JpaRepository<Merchant, UUID> {
}
