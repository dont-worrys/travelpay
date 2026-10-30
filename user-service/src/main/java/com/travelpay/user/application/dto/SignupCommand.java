package com.travelpay.user.application.dto;

import java.util.List;

import com.travelpay.user.domain.model.vo.TermsDocumentId;

/** 회원가입 응용 서비스에 전달할 입력이며, 해시 처리 전 비밀번호를 포함합니다. */
public record SignupCommand(
        String loginEmail,
        String rawPassword,
        String realName,
        String phone,
        List<TermsDocumentId> consents
) {

    public SignupCommand {
        consents = List.copyOf(consents);
    }

    @Override
    public String toString() {
        return "SignupCommand[REDACTED]";
    }
}
