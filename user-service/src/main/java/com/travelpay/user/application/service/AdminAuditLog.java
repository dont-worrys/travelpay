package com.travelpay.user.application.service;

import java.time.Instant;
import java.util.UUID;

import com.travelpay.user.domain.model.vo.UserStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 관리자가 다른 회원이나 약관을 바꾼 기록을 남깁니다. 변경이 커밋된 뒤에만 부릅니다.
 * 수집기가 로거 이름으로 감사 기록만 골라 따로 보관할 수 있게 하고, 성명·이메일·휴대폰 번호 같은 개인정보는 남기지 않습니다.
 */
final class AdminAuditLog {

    private static final Logger log = LoggerFactory.getLogger("travelpay.audit.admin");

    private AdminAuditLog() {
    }

    static void userStatusChanged(UUID actorId, UUID targetUserId, UserStatus fromStatus, UserStatus toStatus) {
        log.atInfo().setMessage("관리자가 회원 상태를 바꿨습니다")
                .addKeyValue("event", "ADMIN_USER_STATUS_CHANGED")
                .addKeyValue("actorId", actorId.toString())
                .addKeyValue("targetUserId", targetUserId.toString())
                .addKeyValue("fromStatus", fromStatus.name())
                .addKeyValue("toStatus", toStatus.name())
                .log();
    }

    static void userSessionsEnded(UUID actorId, UUID targetUserId) {
        log.atInfo().setMessage("관리자가 회원의 기존 세션을 끝냈습니다")
                .addKeyValue("event", "ADMIN_USER_SESSIONS_ENDED")
                .addKeyValue("actorId", actorId.toString())
                .addKeyValue("targetUserId", targetUserId.toString())
                .log();
    }

    static void termsPublished(UUID actorId, String termsCode, String termsVersion, Instant effectiveAt) {
        log.atInfo().setMessage("관리자가 새 약관 버전을 등록했습니다")
                .addKeyValue("event", "ADMIN_TERMS_PUBLISHED")
                .addKeyValue("actorId", actorId.toString())
                .addKeyValue("termsCode", termsCode)
                .addKeyValue("termsVersion", termsVersion)
                .addKeyValue("effectiveAt", effectiveAt.toString())
                .log();
    }
}
