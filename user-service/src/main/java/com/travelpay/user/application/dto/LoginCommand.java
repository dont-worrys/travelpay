package com.travelpay.user.application.dto;

/** 일반 로그인 응용 서비스에 전달할 이메일, 해시 처리 전 비밀번호와 시도 제한에 쓰는 클라이언트 IP입니다. */
public record LoginCommand(String loginEmail, String rawPassword, String clientIp) {

    @Override
    public String toString() {
        return "LoginCommand[REDACTED]";
    }
}
