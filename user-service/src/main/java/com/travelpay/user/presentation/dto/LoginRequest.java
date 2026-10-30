package com.travelpay.user.presentation.dto;

import java.util.Locale;

import com.travelpay.user.application.dto.LoginCommand;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 일반 로그인 입력이며, 비밀번호 일치와 회원 상태 확인은 응용 서비스가 담당합니다. */
public record LoginRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식을 확인해 주세요.")
        @Pattern(regexp = "[^\\s@\\p{Cc}]+@[^\\s@\\p{Cc}]+", message = "이메일 형식을 확인해 주세요.")
        @Size(max = 254, message = "이메일은 254자 이내로 입력해 주세요.")
        String loginEmail,
        @NotBlank(message = "비밀번호를 입력해 주세요.")
        @Size(max = 72, message = "비밀번호는 72자 이내로 입력해 주세요.")
        @Pattern(regexp = "[!-~]+", message = "비밀번호는 공백 없는 영문·숫자·특수문자만 입력해 주세요.")
        String password
) {

    public LoginRequest {
        if (loginEmail != null) {
            loginEmail = loginEmail.strip().toLowerCase(Locale.ROOT);
        }
    }

    public LoginCommand toCommand(String clientIp) {
        return new LoginCommand(loginEmail, password, clientIp);
    }

    @Override
    public String toString() {
        return "LoginRequest[REDACTED]";
    }
}
