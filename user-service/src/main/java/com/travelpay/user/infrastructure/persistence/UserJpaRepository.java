package com.travelpay.user.infrastructure.persistence;

import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA가 구현하는 회원 저장소입니다. */
public interface UserJpaRepository extends JpaRepository<User, UUID> {

    boolean existsByPhone(String phone);
}
