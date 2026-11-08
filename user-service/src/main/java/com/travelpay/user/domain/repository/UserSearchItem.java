package com.travelpay.user.domain.repository;

import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.domain.model.vo.UserStatus;

/** 관리자 회원 검색 결과 한 줄입니다. 비밀번호 로그인 정보가 없는 회원은 loginEmail이 null입니다. */
public record UserSearchItem(UUID userId, String realName, String loginEmail, String phone, UserStatus status,
        AuthRole role) {
}
