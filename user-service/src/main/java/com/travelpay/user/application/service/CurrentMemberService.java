package com.travelpay.user.application.service;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import com.travelpay.common.auth.AuthenticatedUser;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 현재 DB의 회원 존재·활동 상태·역할과, 세션 요청이면 세션 버전까지 확인하는 공통 응용 기능입니다. */
@Service
public class CurrentMemberService {

    private final UserRepository userRepository;
    private final TransactionTemplate readTransactions;

    public CurrentMemberService(UserRepository userRepository, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    public AuthenticatedUser requireActive(UUID userId) {
        return readActive(userId, user -> new AuthenticatedUser(user.getId(), user.getRole()));
    }

    // 세션 요청용. 정지·종료 안내(403)를 먼저 하고, 로그인 뒤 세션 버전이 바뀌었으면 다시 로그인(401)
    public AuthenticatedUser requireActiveSession(UUID userId, long sessionVersion) {
        return readActive(userId, user -> {
            if (user.getSessionVersion() != sessionVersion) {
                throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
            }
            return new AuthenticatedUser(user.getId(), user.getRole());
        });
    }

    // OSIV가 꺼져 있으므로 엔티티는 트랜잭션 밖으로 내보내지 않음. 호출한 쪽의 확인·변환까지 이 안에서 끝냄
    private AuthenticatedUser readActive(UUID userId, Function<User, AuthenticatedUser> toResult) {
        try {
            return Objects.requireNonNull(readTransactions.execute(status -> {
                var user = userRepository.findById(userId)
                        .orElseThrow(() -> new BusinessException(CommonErrorCode.UNAUTHORIZED));
                user.getStatus().requireActive();
                return toResult.apply(user);
            }));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }
}
