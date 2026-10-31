package com.travelpay.user.common.security.session;

import java.time.Clock;
import java.util.List;

import com.travelpay.user.application.dto.LoginResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/** 로그인 검증 후 세션 ID·CSRF를 갱신하고 인증 정보를 HTTP 세션에 저장합니다. */
@Component
public class SessionLoginHandler {

    private final SecurityContextRepository securityContextRepository;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final Clock clock;

    public SessionLoginHandler(SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository, Clock clock) {
        this.securityContextRepository = securityContextRepository;
        this.clock = clock;
        this.sessionAuthenticationStrategy = new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    public void onLoginSuccess(LoginResult result, HttpServletRequest request, HttpServletResponse response) {
        var principal = new UserSessionPrincipal(result.userId(), result.role(), clock.instant());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));

        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }
}
