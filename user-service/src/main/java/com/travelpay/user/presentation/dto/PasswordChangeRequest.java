package com.travelpay.user.presentation.dto;

import com.travelpay.user.application.dto.PasswordChangeCommand;
import com.travelpay.user.common.validation.ValidPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 비밀번호 변경 입력이며, 현재 비밀번호 일치 확인은 응용 서비스가 담당합니다. */
public record PasswordChangeRequest(
        @NotBlank(message = "현재 비밀번호를 입력해 주세요.")
        @Size(max = 72, message = "현재 비밀번호는 72자 이내로 입력해 주세요.")
        @Pattern(regexp = "[!-~]+", message = "현재 비밀번호는 공백 없는 영문·숫자·특수문자만 입력해 주세요.")
        String currentPassword,
        @ValidPassword
        String newPassword
) {

    public PasswordChangeCommand toCommand(String clientIp) {
        return new PasswordChangeCommand(currentPassword, newPassword, clientIp);
    }

    @Override
    public String toString() {
        return "PasswordChangeRequest[REDACTED]";
    }
}
