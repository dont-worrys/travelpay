package com.travelpay.user.domain.repository;

import java.util.UUID;

import com.travelpay.user.domain.model.vo.UserStatus;

/** 관리자 회원 검색 조건입니다. 값이 있는 조건만 정확히 일치로 적용합니다. */
public record UserSearchCondition(UUID userId, String loginEmail, String phone, UserStatus status) {
}
