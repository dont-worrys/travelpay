package com.travelpay.user.common.security.session;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;

/** 로그아웃 요청의 Redis 세션을 삭제한 뒤 HTTP 세션과 인증 정보를 정리합니다. */
@Component
public class SessionLogoutHandler {

    private final SessionRepository<? extends Session> sessionRepository;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public SessionLogoutHandler(SessionRepository<? extends Session> sessionRepository,
            SecurityContextRepository securityContextRepository) {
        this.sessionRepository = sessionRepository;
        this.logoutHandler.setSecurityContextRepository(securityContextRepository);
    }

    /** 인가 규칙이 정식 회원 세션을 확인한 요청에서만 호출합니다. */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        try {
            // Spring Session의 invalidate는 쿠키 만료를 먼저 표시한 뒤 Redis에서 삭제합니다.
            // 삭제 실패가 쿠키 만료로 이어지지 않도록 Redis에서 먼저 지웁니다.
            sessionRepository.deleteById(session.getId());
            logoutHandler.logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        }
        catch (DataAccessException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }
}
