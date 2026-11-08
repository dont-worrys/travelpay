package com.travelpay.user.application.dto;

import java.util.UUID;

import com.travelpay.user.domain.model.vo.UserStatus;

/** 관리자 회원 검색 요청입니다. 이메일·휴대폰 번호는 서비스가 저장 형식으로 맞춘 뒤 비교합니다. */
public record AdminUserSearchCommand(UUID userId, String loginEmail, String phone, UserStatus status, int page,
        int size) {
}
