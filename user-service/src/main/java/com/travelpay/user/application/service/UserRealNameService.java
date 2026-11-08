package com.travelpay.user.application.service;

import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.UserRealNameResult;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 다른 업무 서비스가 내부 API로 요청하는 회원 ID와 성명을 조회합니다. */
@Service
public class UserRealNameService {

    private final UserRepository userRepository;
    private final TransactionTemplate readTransactions;
    private final RecipientLookupLimiter lookupLimiter;

    public UserRealNameService(UserRepository userRepository, PlatformTransactionManager transactionManager,
            RecipientLookupLimiter lookupLimiter) {
        this.userRepository = userRepository;
        this.lookupLimiter = lookupLimiter;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    /** 송금 수취인 실명 조회: 다른 회원의 상태가 드러나지 않도록 없는 회원과 ACTIVE가 아닌 회원을 같은 404로 처리합니다. */
    public UserRealNameResult getRecipient(UUID requesterId, UUID recipientId) {
        if (requesterId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        if (recipientId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        // Redis 응답을 기다리는 동안 DB 연결을 점유하지 않도록 읽기 트랜잭션 전에 확인합니다.
        try {
            if (!lookupLimiter.tryAcquire(requesterId)) {
                throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
            }
        }
        catch (DataAccessException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
        return readInTransaction(status -> userRepository.findById(recipientId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(UserRealNameResult::from)
                .orElseThrow(() -> new BusinessException(UserErrorCode.RECIPIENT_NOT_FOUND)));
    }

    /** 충전 예금주(본인 실명) 조회: 본인만 조회하며, 정지·종료 상태는 해당 회원에게 403으로 알립니다. */
    public UserRealNameResult getFundingIdentity(UUID requesterId, UUID userId) {
        if (requesterId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        if (!requesterId.equals(userId)) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
        return readInTransaction(status -> {
            var user = userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(UserErrorCode.NOT_FOUND));
            user.getStatus().requireActive();
            return UserRealNameResult.from(user);
        });
    }

    /** 트랜잭션 시작·종료 실패까지 503으로 바꾸도록 트랜잭션을 이 메서드 안에서 실행합니다. */
    private UserRealNameResult readInTransaction(TransactionCallback<UserRealNameResult> lookup) {
        try {
            return Objects.requireNonNull(readTransactions.execute(lookup));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }
}
