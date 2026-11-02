package com.travelpay.gateway.auth;

import com.travelpay.common.auth.AuthenticatedUser;
import org.springframework.http.HttpStatusCode;

/** 세션 확인 API의 결과. 확인된 회원이거나, 브라우저에 그대로 돌려줄 거절 응답입니다. */
sealed interface SessionVerification {

    // 확인된 회원(ID와 지금 DB의 역할)
    record Verified(AuthenticatedUser user) implements SessionVerification {
    }

    // user-service가 거절한 응답(401·403·409)의 상태와 본문
    record Rejected(HttpStatusCode status, byte[] body) implements SessionVerification {
    }
}
