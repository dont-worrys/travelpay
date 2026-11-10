package com.travelpay.user.presentation.dto;

import java.util.UUID;

import com.travelpay.user.application.dto.AdminUserSearchCommand;
import com.travelpay.user.domain.model.vo.UserStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 관리자 회원 검색 조건입니다. 값을 준 조건만 정확히 일치로 적용하며, page는 0부터, size는 기본 20·최대 100입니다. */
public record AdminUserSearchRequest(
        UUID userId,
        @Size(max = 254, message = "이메일은 254자 이내로 입력해 주세요.")
        // DB가 받지 못하는 제어 문자(NUL 등)는 저장소 장애(503)가 아닌 입력 오류(400)로 돌려줌
        @Pattern(regexp = "\\P{Cc}*", message = "이메일에 제어 문자를 넣을 수 없습니다.")
        String loginEmail,
        // 빈 값은 조건 없음으로 받고, 하이픈 없이 넣어도 찾도록 받아 서비스가 저장 형식(010-0000-0000)으로 맞춤
        @Pattern(regexp = "|010-?[0-9]{4}-?[0-9]{4}", message = "휴대폰 번호는 010-1234-5678 형식으로 입력해 주세요.")
        String phone,
        UserStatus status,
        @Min(value = 0, message = "page는 0 이상이어야 합니다.")
        Integer page,
        @Min(value = 1, message = "size는 1 이상이어야 합니다.")
        @Max(value = 100, message = "size는 100 이하여야 합니다.")
        Integer size
) {

    private static final int DEFAULT_SIZE = 20;

    public AdminUserSearchCommand toCommand() {
        return new AdminUserSearchCommand(userId, loginEmail, phone, status, page == null ? 0 : page,
                size == null ? DEFAULT_SIZE : size);
    }
}
