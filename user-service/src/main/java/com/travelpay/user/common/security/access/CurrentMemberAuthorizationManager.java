package com.travelpay.user.common.security.access;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.service.CurrentMemberService;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.common.security.session.UserSessionPrincipal;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/**
 *  Spring Security가 "이 요청을 통과시킬까?"를 물을 때 답하는 인가 판단기
 *   세션에 저장된 로그인 당시 정보가 아니라, 지금 DB에 있는 회원 상태와 역할로 판단
 *  보호 요청마다 DB의 현재 회원 상태·권한을 확인하고 이번 요청의 인증 정보를 맞춥니다.
 */
public final class CurrentMemberAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();
    private final CurrentMemberService currentMemberService;
    private final Mode mode;

    private CurrentMemberAuthorizationManager(CurrentMemberService currentMemberService, Mode mode) {
        this.currentMemberService = Objects.requireNonNull(currentMemberService);
        this.mode = mode;
    }

    public static CurrentMemberAuthorizationManager activeMembers(CurrentMemberService service) {
        return new CurrentMemberAuthorizationManager(service, Mode.ACTIVE);
    }

    public static CurrentMemberAuthorizationManager masters(CurrentMemberService service) {
        return new CurrentMemberAuthorizationManager(service, Mode.MASTER);
    }

    public static CurrentMemberAuthorizationManager sessionMembers(CurrentMemberService service) {
        return new CurrentMemberAuthorizationManager(service, Mode.SESSION_ONLY);
    }

    @Override
    public AuthorizationResult authorize(Supplier<? extends @Nullable Authentication> authentication,
            RequestAuthorizationContext context) {
        var authenticated = authentication.get();
        if (!trustResolver.isAuthenticated(authenticated)
                || !(authenticated.getPrincipal() instanceof UserSessionPrincipal principal)) {
            throw new InsufficientAuthenticationException(CommonErrorCode.UNAUTHORIZED.getMessage());
        }
        if (mode == Mode.SESSION_ONLY) {
            return new AuthorizationDecision(true);
        }

        var current = requireActive(principal);
        // 새 컨텍스트만 만들어 현재 요청에 적용합니다. 로그인 시각·세션 ID·CSRF는 갱신하지 않습니다.
        var refreshed = new UserSessionPrincipal(current.userId(), current.role(), principal.authenticatedAt());
        var updated = UsernamePasswordAuthenticationToken.authenticated(refreshed, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + current.role().name())));
        var refreshedContext = SecurityContextHolder.createEmptyContext();
        refreshedContext.setAuthentication(updated);
        SecurityContextHolder.setContext(refreshedContext);
        return new AuthorizationDecision(mode != Mode.MASTER || current.role() == AuthRole.MASTER);
    }

    private AuthenticatedUser requireActive(UserSessionPrincipal principal) {
        try {
            return currentMemberService.requireActive(principal.userId());
        }
        catch (BusinessException exception) {
            var code = exception.getErrorCode();
            if (code == CommonErrorCode.UNAUTHORIZED) {
                throw new InsufficientAuthenticationException(code.getMessage(), exception);
            }
            if (code == CommonErrorCode.SERVICE_UNAVAILABLE) {
                throw new AuthenticationServiceException(code.getMessage(), exception);
            }
            if (code == UserErrorCode.ACCOUNT_SUSPENDED || code == UserErrorCode.ACCOUNT_CLOSED) {
                throw new MemberAccessDeniedException((UserErrorCode) code, exception);
            }
            throw exception;
        }
    }

    private enum Mode {
        ACTIVE,
        MASTER,
        SESSION_ONLY
    }
}
