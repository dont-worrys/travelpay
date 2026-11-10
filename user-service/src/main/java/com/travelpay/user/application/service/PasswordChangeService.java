package com.travelpay.user.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.PasswordChangeCommand;
import com.travelpay.user.common.config.PasswordChangeProperties;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.common.validation.ValidPassword;
import com.travelpay.user.domain.model.entity.UserCredentials;
import com.travelpay.user.domain.repository.UserCredentialsRepository;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 현재 비밀번호 확인·새 해시 계산·자격 증명 재확인 후 DB 변경을 조정합니다. */
@Service
@EnableConfigurationProperties(PasswordChangeProperties.class)
public class PasswordChangeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordChangeService.class);

    private final UserCredentialsRepository credentialsRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptLimiter attemptLimiter;
    private final CurrentMemberService currentMemberService;
    private final Validator validator;
    private final Clock clock;
    private final TransactionTemplate readTransactions;
    private final TransactionTemplate writeTransactions;

    public PasswordChangeService(UserCredentialsRepository credentialsRepository, PasswordEncoder passwordEncoder,
            PlatformTransactionManager transactionManager, LoginAttemptLimiter attemptLimiter,
            CurrentMemberService currentMemberService, Validator validator, Clock clock, PasswordChangeProperties properties) {
        this.credentialsRepository = credentialsRepository;
        this.passwordEncoder = passwordEncoder;
        this.attemptLimiter = attemptLimiter;
        this.currentMemberService = currentMemberService;
        this.validator = validator;
        this.clock = clock;
        var timeoutSeconds = Math.toIntExact(properties.transactionTimeout().getSeconds());
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
        this.readTransactions.setTimeout(timeoutSeconds);
        this.writeTransactions = new TransactionTemplate(transactionManager);
        this.writeTransactions.setTimeout(timeoutSeconds);
    }

    /** 해시 연산과 Redis 시도 기록이 호출자의 DB 트랜잭션 안으로 들어가지 않게 합니다. */
    @Transactional(propagation = Propagation.NEVER)
    public void changePassword(UUID userId, PasswordChangeCommand command) {
        if (userId == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        validateInput(command);
        try {
            var initial = Objects.requireNonNull(readTransactions.execute(status -> {
                currentMemberService.requireActive(userId);
                return credentialsRepository.findByUserId(userId).map(CredentialsSnapshot::from)
                        .orElseThrow(() -> new BusinessException(UserErrorCode.CREDENTIAL_CONFLICT));
            }));
            verifyCurrentPassword(initial, command);
            if (command.currentRawPassword().equals(command.newRawPassword())) {
                throw new BusinessException(UserErrorCode.NEW_PASSWORD_SAME_AS_CURRENT);
            }

            var newHash = passwordEncoder.encode(command.newRawPassword());
            // executeWithoutResult는 커밋까지 끝난 뒤 반환합니다. HTTP 세션 갱신은 호출자가 그 뒤 수행합니다.
            writeTransactions.executeWithoutResult(status -> changeStoredPassword(userId, initial, newHash));
        }
        catch (DataAccessException | TransactionException exception) {
            // 비밀번호·해시·이메일·IP 대신 원인 종류만 기록합니다.
            log.error("비밀번호 변경 저장소 오류: {}", exception.getClass().getSimpleName());
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }

    private void verifyCurrentPassword(CredentialsSnapshot initial, PasswordChangeCommand command) {
        var email = initial.loginEmail();
        var clientIp = command.clientIp();
        if (!attemptLimiter.tryStart(email, clientIp)) {
            throw new BusinessException(CommonErrorCode.TOO_MANY_REQUESTS);
        }
        boolean matches;
        try {
            matches = passwordEncoder.matches(command.currentRawPassword(), initial.passwordHash());
        }
        catch (IllegalArgumentException exception) {
            attemptLimiter.cancel(email, clientIp);
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
        catch (RuntimeException exception) {
            attemptLimiter.cancel(email, clientIp);
            throw exception;
        }
        if (!matches) {
            attemptLimiter.recordFailure(email, clientIp);
            throw new BusinessException(UserErrorCode.CURRENT_PASSWORD_MISMATCH);
        }
        // 비밀번호 확인의 성공 기록을 DB 변경 전에 끝내 사후 Redis 장애와 DB 변경 완료를 혼동하지 않습니다.
        attemptLimiter.recordSuccess(email, clientIp);
    }

    private void changeStoredPassword(UUID userId, CredentialsSnapshot initial, String newHash) {
        var current = credentialsRepository.findByUserIdForUpdate(userId).orElse(null);
        currentMemberService.requireActive(userId);
        if (current == null || !initial.passwordHash().equals(current.getPasswordHash())
                || !initial.passwordChangedAt().equals(current.getPasswordChangedAt())) {
            throw new BusinessException(UserErrorCode.CREDENTIAL_CONFLICT);
        }
        current.changePassword(newHash, clock.instant());
        credentialsRepository.save(current);
    }

    private void validateInput(PasswordChangeCommand command) {
        if (command == null || !isSupportedCurrentPassword(command.currentRawPassword())
                || command.clientIp() == null || command.clientIp().isBlank()
                || !validator.validate(new NewPasswordInput(command.newRawPassword())).isEmpty()) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
    }

    private boolean isSupportedCurrentPassword(String rawPassword) {
        return rawPassword != null && rawPassword.length() <= 72 && rawPassword.matches("[!-~]+");
    }

    private record CredentialsSnapshot(String loginEmail, String passwordHash, Instant passwordChangedAt) {

        private static CredentialsSnapshot from(UserCredentials credentials) {
            return new CredentialsSnapshot(credentials.getLoginEmail(), credentials.getPasswordHash(),
                    credentials.getPasswordChangedAt());
        }

        @Override
        public String toString() { return "CredentialsSnapshot[REDACTED]"; }
    }

    private record NewPasswordInput(@ValidPassword String value) {
        @Override
        public String toString() { return "NewPasswordInput[REDACTED]"; }
    }
}
