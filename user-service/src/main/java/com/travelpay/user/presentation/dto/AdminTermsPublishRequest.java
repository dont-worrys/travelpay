package com.travelpay.user.presentation.dto;

import java.time.Instant;

import com.travelpay.user.application.dto.AdminTermsPublishCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 관리자 새 약관 버전 등록 요청입니다. 제목·본문의 세부 규칙은 약관 도메인이 한 번 더 확인합니다. */
public record AdminTermsPublishRequest(
        // 기존 코드(SERVICE_TERMS 등)와 같은 형식만 받아, 대소문자만 다른 코드가 따로 생기지 않게 함
        @NotNull(message = "약관 코드를 입력해 주세요.")
        @Pattern(regexp = "[A-Z][A-Z0-9_]{0,39}", message = "약관 코드는 대문자로 시작하는 40자 이내의 대문자·숫자·밑줄이어야 합니다.")
        String termsCode,
        @NotBlank(message = "약관 버전을 입력해 주세요.")
        @Size(max = 40, message = "약관 버전은 40자 이내로 입력해 주세요.")
        String termsVersion,
        @NotBlank(message = "약관 제목을 입력해 주세요.")
        @Size(max = 200, message = "약관 제목은 200자 이내로 입력해 주세요.")
        String title,
        @NotBlank(message = "약관 본문을 입력해 주세요.")
        String content,
        @NotNull(message = "필수 동의 여부를 입력해 주세요.")
        Boolean isRequired,
        @NotNull(message = "시행 시각을 입력해 주세요.")
        Instant effectiveAt
) {

    public AdminTermsPublishCommand toCommand() {
        return new AdminTermsPublishCommand(termsCode, termsVersion, title, content, isRequired, effectiveAt);
    }
}
