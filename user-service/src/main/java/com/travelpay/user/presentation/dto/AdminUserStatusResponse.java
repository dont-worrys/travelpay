package com.travelpay.user.presentation.dto;

import java.util.UUID;

import com.travelpay.user.domain.model.vo.UserStatus;

/** 관리자 회원 정지·해제 응답입니다. 이미 요청한 상태였다면 바꾸지 않고 현재 상태를 돌려줍니다. */
public record AdminUserStatusResponse(UUID userId, UserStatus status) {
}
