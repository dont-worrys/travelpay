package com.travelpay.user.application.dto;

/** 관리자 약관 목록 조회 요청입니다. 약관 코드는 값이 있을 때만 정확히 일치로 거릅니다. */
public record AdminTermsListCommand(String termsCode, int page, int size) {
}
