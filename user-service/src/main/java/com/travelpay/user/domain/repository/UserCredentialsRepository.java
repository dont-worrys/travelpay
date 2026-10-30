package com.travelpay.user.domain.repository;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.UserCredentials;

/**
 * 자체 로그인 수단의 저장·조회 계약입니다.
 * 이메일 조회·중복 확인에는 앞뒤 공백을 제거하고 소문자로 통일한 값을 전달합니다.
 */
public interface UserCredentialsRepository {

    /** 자체 로그인 수단을 저장하며, 이메일 중복은 User의 업무 예외로 거절합니다. */
    UserCredentials save(UserCredentials credentials);

    /** 이메일로 로그인 수단과 연결된 회원을 조회하며, 없으면 빈 결과를 반환합니다. */
    Optional<UserCredentials> findByLoginEmail(String loginEmail);

    /** 회원 ID로 자체 로그인 수단을 조회하며, 등록하지 않은 회원이면 빈 결과를 반환합니다. */
    Optional<UserCredentials> findByUserId(UUID userId);

    /** 해당 이메일로 등록된 자체 로그인 수단이 있는지 확인합니다. */
    boolean existsByLoginEmail(String loginEmail);
}
