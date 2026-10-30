package com.travelpay.user.common.security.session;

import java.util.Optional;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.auth.CurrentUserProvider;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Spring Security가 복원한 User 세션의 인증 정보를 공통 요청자 정보로 전달합니다.
 * Redis·회원 DB를 직접 조회하지 않고 인증된 정식 회원 Principal만 읽습니다.
 */
@Component
public class SessionCurrentUserProvider implements CurrentUserProvider {

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    @Override
    public Optional<AuthenticatedUser> findCurrentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!trustResolver.isAuthenticated(authentication)) {
            return Optional.empty();
        }
        if (!(authentication.getPrincipal() instanceof UserSessionPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedUser(principal.userId(), principal.role()));
    }
}
