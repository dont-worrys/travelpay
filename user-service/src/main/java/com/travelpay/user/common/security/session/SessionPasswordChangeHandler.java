package com.travelpay.user.common.security.session;

import java.util.List;

import com.travelpay.common.error.BusinessException;
import com.travelpay.user.common.error.UserErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/** 비밀번호 DB 변경이 완료된 뒤 현재 인증 정보를 유지하면서 세션 ID·CSRF를 갱신합니다. */
@Component
public class SessionPasswordChangeHandler {

    /** 후속 세션 저장 실패에서도 DB 변경 완료를 알리는 서버 내부 요청 속성입니다. */
    public static final String ACCOUNT_UPDATED_ATTRIBUTE = SessionPasswordChangeHandler.class.getName() + ".ACCOUNT_UPDATED";

    private static final Logger log = LoggerFactory.getLogger(SessionPasswordChangeHandler.class);
    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    public SessionPasswordChangeHandler(SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository) {
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    /** PasswordChangeService의 DB 커밋 완료를 확인한 뒤에만 호출합니다. */
    public void onPasswordChangeSuccess(HttpServletRequest request, HttpServletResponse response) {
        request.setAttribute(ACCOUNT_UPDATED_ATTRIBUTE, Boolean.TRUE);
        try {
            var context = SecurityContextHolder.getContext();
            var authentication = context.getAuthentication();
            if (!trustResolver.isAuthenticated(authentication)
                    || !(authentication.getPrincipal() instanceof UserSessionPrincipal)
                    || request.getSession(false) == null) {
                throw new IllegalStateException("A current member session is required after the account update");
            }
            // 현재 Principal을 그대로 사용하므로 최초 authenticatedAt을 다시 기록하지 않습니다.
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
            securityContextRepository.saveContext(context, request, response);
        }
        catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
            log.error("비밀번호 변경 후 세션 갱신 오류: {}", exception.getClass().getSimpleName());
            throw new BusinessException(UserErrorCode.ACCOUNT_UPDATED_SESSION_UNAVAILABLE, exception);
        }
    }
}
