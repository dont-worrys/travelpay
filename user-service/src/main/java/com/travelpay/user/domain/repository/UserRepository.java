package com.travelpay.user.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;

/** 회원 공통 정보의 저장·조회 계약입니다. */
public interface UserRepository {

    /** 회원을 저장하며, 휴대폰 번호 중복은 User의 업무 예외로 거절합니다. */
    User save(User user);

    /** 회원 ID로 조회하며, 존재하지 않으면 빈 결과를 반환합니다. */
    Optional<User> findById(UUID userId);

    /** 해당 휴대폰 번호로 등록된 회원이 있는지 확인합니다. */
    boolean existsByPhone(String phone);
}
