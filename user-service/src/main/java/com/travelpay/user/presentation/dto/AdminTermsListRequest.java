package com.travelpay.user.presentation.dto;

import com.travelpay.user.application.dto.AdminTermsListCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 관리자 약관 목록 조회 조건입니다. 약관 코드는 값을 줬을 때만 거르며, page는 0부터, size는 기본 20·최대 100입니다. */
public record AdminTermsListRequest(
        @Size(max = 40, message = "약관 코드는 40자 이내로 입력해 주세요.")
        // DB가 받지 못하는 제어 문자(NUL 등)는 저장소 장애(503)가 아닌 입력 오류(400)로 돌려줌
        @Pattern(regexp = "\\P{Cc}*", message = "약관 코드에 제어 문자를 넣을 수 없습니다.")
        String termsCode,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.")
        Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 100, message = "size는 100 이하여야 합니다.")
        Integer size
) {

    private static final int DEFAULT_SIZE = 20;

    public AdminTermsListCommand toCommand() {
        return new AdminTermsListCommand(termsCode, page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }
}
