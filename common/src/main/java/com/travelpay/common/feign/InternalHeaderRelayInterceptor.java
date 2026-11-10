package com.travelpay.common.feign;

import java.util.Objects;
import java.util.Optional;

import com.travelpay.common.auth.AuthHeaders;
import com.travelpay.common.auth.CurrentUserProvider;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 사용자 요청을 처리하는 중에 하는 Feign 호출에 User-Id·User-Role·Request-Id를 이어 보냅니다.
 * 요청자별 제한·본인 확인처럼 요청자가 필요한 내부 API와 요청 추적에 씁니다.
 * <ul>
 *     <li>요청자는 들어온 헤더를 다시 읽지 않고 검증된 {@link CurrentUserProvider}의 값을 보냅니다.
 *         이 값은 요청을 시작한 사용자라서, 받는 쪽은 대상 회원을 경로·파라미터로 받고 이 값으로 소유권을 판단하지 않습니다.</li>
 *     <li>클라이언트가 직접 지정한 헤더는 덮어쓰지 않습니다. User-Id·User-Role은 한 쌍이라 하나라도 있으면 둘 다 그대로 둡니다.</li>
 *     <li>들어온 요청이 없는 호출(Kafka·스케줄러)에는 붙이지 않으므로, 그런 호출은 헤더를 직접 지정해야 합니다.</li>
 * </ul>
 */
public class InternalHeaderRelayInterceptor implements RequestInterceptor {

    private final CurrentUserProvider currentUserProvider;

    public InternalHeaderRelayInterceptor(CurrentUserProvider currentUserProvider) {
        this.currentUserProvider = Objects.requireNonNull(currentUserProvider, "currentUserProvider");
    }

    @Override
    public void apply(RequestTemplate template) {
        // RequestTemplate의 헤더 이름은 대소문자를 구분하지 않습니다.
        boolean userHeadersSet = template.headers().containsKey(AuthHeaders.USER_ID)
                || template.headers().containsKey(AuthHeaders.USER_ROLE);
        if (!userHeadersSet) {
            currentUserProvider.findCurrentUser().ifPresent(user -> {
                template.header(AuthHeaders.USER_ID, user.userId().toString());
                template.header(AuthHeaders.USER_ROLE, user.role().name());
            });
        }
        if (!template.headers().containsKey(AuthHeaders.REQUEST_ID)) {
            currentRequestId().ifPresent(requestId -> template.header(AuthHeaders.REQUEST_ID, requestId));
        }
    }

    private static Optional<String> currentRequestId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        String requestId = attributes.getRequest().getHeader(AuthHeaders.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(requestId.strip());
    }
}
