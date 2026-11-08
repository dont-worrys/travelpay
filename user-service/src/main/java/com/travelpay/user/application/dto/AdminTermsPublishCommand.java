package com.travelpay.user.application.dto;

import java.time.Instant;

/** 관리자가 새 약관 버전을 등록하는 요청입니다. */
public record AdminTermsPublishCommand(String termsCode, String termsVersion, String title, String content,
        boolean required, Instant effectiveAt) {
}
