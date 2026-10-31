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

    public UserRealNameService(UserRepository userRepository, PlatformTransactionManager transactionManager) {
        this.userRepository = userRepository;
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
    }

    /** 명세 I02 송금 수취인 확인: 다른 회원의 상태가 드러나지 않도록 없는 회원과 ACTIVE가 아닌 회원을 같은 404로 처리합니다. */
    public UserRealNameResult getRecipient(UUID userId) {
        return readInTransaction(status -> userRepository.findById(userId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(UserRealNameResult::from)
                .orElseThrow(() -> new BusinessException(UserErrorCode.RECIPIENT_NOT_FOUND)));
    }

    /** 명세 I03 충전 예금주 확인: 충전 요청자 본인을 조회하므로 정지·종료 상태를 숨기지 않고 그 상태의 403으로 알립니다. */
    public UserRealNameResult getFundingIdentity(UUID userId) {
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
