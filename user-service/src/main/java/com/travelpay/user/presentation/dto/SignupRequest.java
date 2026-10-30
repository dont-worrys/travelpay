package com.travelpay.user.presentation.dto;

import java.util.List;
import java.util.Locale;

import com.travelpay.user.application.dto.SignupCommand;
import com.travelpay.user.common.validation.ValidPassword;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.CodePointLength;

/** 일반 회원가입 입력이며, 필수 약관과 현재 버전 확인은 응용 서비스에서 처리합니다. */
public record SignupRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "이메일 형식을 확인해 주세요.")
        @Pattern(regexp = "[^\\s@\\p{Cc}]+@[^\\s@\\p{Cc}]+", message = "이메일 형식을 확인해 주세요.")
        @Size(max = 254, message = "이메일은 254자 이내로 입력해 주세요.")
        String loginEmail,
        @ValidPassword
        String password,
        @NotBlank(message = "성함을 입력해 주세요.")
        @CodePointLength(max = 40, message = "성함은 40자 이내로 입력해 주세요.")
        @Pattern(regexp = "[^\\p{Cc}\\p{Cs}]+", message = "성함에 올바른 문자를 입력해 주세요.")
        String realName,
        @NotBlank(message = "휴대폰 번호를 입력해 주세요.")
        @Pattern(regexp = "010-[0-9]{4}-[0-9]{4}", message = "휴대폰 번호는 010-1234-5678 형식으로 입력해 주세요.")
        String phone,
        @NotNull(message = "약관 동의 목록을 전달해 주세요.")
        List<@NotNull(message = "약관 동의 항목은 비어 있을 수 없습니다.") @Valid Consent> consents
) {

    public SignupRequest {
        if (loginEmail != null) {
            loginEmail = loginEmail.strip().toLowerCase(Locale.ROOT);
        }
        if (realName != null) {
            realName = realName.strip();
        }
    }

    /** 입력 검증을 통과한 요청을 응용 계층의 회원가입 입력으로 변환합니다. */
    public SignupCommand toCommand() {
        return new SignupCommand(loginEmail, password, realName, phone,
                consents.stream()
                        .map(consent -> new TermsDocumentId(consent.termsCode(), consent.termsVersion()))
                        .toList());
    }

    @Override
    public String toString() {
        return "SignupRequest[REDACTED]";
    }

    /** 사용자가 동의한 약관 코드와 버전입니다. */
    public record Consent(
            @NotBlank(message = "약관 코드를 입력해 주세요.")
            @CodePointLength(max = 40, message = "약관 코드는 40자 이내로 입력해 주세요.")
            @Pattern(regexp = "[^\\p{Cc}\\p{Cs}]+", message = "약관 코드에 올바른 문자를 입력해 주세요.")
            String termsCode,
            @NotBlank(message = "약관 버전을 입력해 주세요.")
            @CodePointLength(max = 40, message = "약관 버전은 40자 이내로 입력해 주세요.")
            @Pattern(regexp = "[^\\p{Cc}\\p{Cs}]+", message = "약관 버전에 올바른 문자를 입력해 주세요.")
            String termsVersion
    ) {

        public Consent {
            if (termsCode != null) {
                termsCode = termsCode.strip();
            }
            if (termsVersion != null) {
                termsVersion = termsVersion.strip();
            }
        }
    }
}
