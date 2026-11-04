package com.travelpay.user.application.dto;

/** 비밀번호 변경 응용 서비스에 전달할 해시 처리 전 비밀번호와 시도 제한용 클라이언트 IP입니다. */
public record PasswordChangeCommand(String currentRawPassword, String newRawPassword, String clientIp) {

    @Override
    public String toString() {
        return "PasswordChangeCommand[REDACTED]";
    }
}
