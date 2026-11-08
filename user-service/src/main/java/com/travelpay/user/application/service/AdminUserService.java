package com.travelpay.user.application.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.AdminUserSearchCommand;
import com.travelpay.user.application.dto.AdminUserSearchResult;
import com.travelpay.user.common.config.AdminProperties;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.User;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserRepository;
import com.travelpay.user.domain.repository.UserSearchCondition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 관리자의 회원 검색, 정지·해제, 세션 종료입니다. 요청마다 짧은 DB 트랜잭션 하나로 처리하고, Redis나 다른 서비스는 부르지 않습니다. */
@Service
@EnableConfigurationProperties(AdminProperties.class)
public class AdminUserService {

    private final UserRepository userRepository;
    private final TransactionTemplate readTransactions;
    private final TransactionTemplate writeTransactions;

    public AdminUserService(UserRepository userRepository, PlatformTransactionManager transactionManager,
            AdminProperties properties) {
        this.userRepository = userRepository;
        var timeoutSeconds = Math.toIntExact(properties.transactionTimeout().getSeconds());
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
        this.readTransactions.setTimeout(timeoutSeconds);
        this.writeTransactions = new TransactionTemplate(transactionManager);
        this.writeTransactions.setTimeout(timeoutSeconds);
    }

    /** 회원 검색: 값을 준 조건만 정확히 일치로 찾고, 가입 최신순으로 한 페이지와 다음 페이지 유무를 돌려줍니다. */
    public AdminUserSearchResult search(AdminUserSearchCommand command) {
        var offset = (long) command.page() * command.size();
        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        var condition = new UserSearchCondition(command.userId(), normalizeEmail(command.loginEmail()),
                normalizePhone(command.phone()), command.status());
        try {
            return Objects.requireNonNull(readTransactions.execute(status -> {
                // 다음 페이지가 있는지 알려고 한 개 더 읽음. 전체 개수는 세지 않음
                var found = userRepository.search(condition, (int) offset, command.size() + 1);
                var hasNext = found.size() > command.size();
                var members = (hasNext ? found.subList(0, command.size()) : found).stream()
                        .map(AdminUserSearchResult.Member::from).toList();
                return new AdminUserSearchResult(members, command.page(), command.size(), hasNext);
            }));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }

    /**
     * 회원 정지·해제: ACTIVE ↔ SUSPENDED만 바꿉니다. 상태가 바뀌면(정지·해제) 세션 버전을 올려 기존 세션을 끝냅니다.
     * 해제 때도 올리므로, 이 API 밖(직접 SQL 등)에서 정지된 회원의 예전 세션도 해제 뒤 다시 쓰이지 않습니다.
     * 마지막 ACTIVE MASTER 보호: 정지는 늘 다른 ACTIVE MASTER가 하고, 두 행을 잠근 뒤 요청자를 다시 확인하므로
     * 정지가 커밋되는 순간 최소 한 명(요청자)은 ACTIVE MASTER로 남습니다.
     */
    public UserStatus changeStatus(UUID actorId, UUID targetId, UserStatus requested) {
        if (actorId.equals(targetId) && requested == UserStatus.SUSPENDED) {
            throw new BusinessException(UserErrorCode.MASTER_PROTECTION_CONFLICT);
        }
        StatusChange change;
        try {
            change = Objects.requireNonNull(writeTransactions.execute(status -> {
                var locked = userRepository.findAllByIdForUpdate(List.of(actorId, targetId)).stream()
                        .collect(Collectors.toMap(User::getId, Function.identity()));
                requireActiveMaster(locked.get(actorId));
                var target = locked.get(targetId);
                if (target == null) {
                    throw new BusinessException(UserErrorCode.NOT_FOUND);
                }
                var before = target.getStatus();
                if (target.changeStatus(requested)) {
                    userRepository.save(target);
                    userRepository.increaseSessionVersion(targetId);
                }
                return new StatusChange(before, target.getStatus());
            }));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
        // 커밋된 변경만 감사 기록으로 남기고, 이미 그 상태였던 요청은 남기지 않음
        if (change.before() != change.after()) {
            AdminAuditLog.userStatusChanged(actorId, targetId, change.before(), change.after());
        }
        return change.after();
    }

    /** 회원 세션 종료: 세션 버전을 올려, 지금까지 발급된 대상 회원의 세션이 다음 요청부터 401이 되게 합니다. */
    public void endSessions(UUID actorId, UUID userId) {
        try {
            writeTransactions.executeWithoutResult(status -> {
                if (!userRepository.increaseSessionVersion(userId)) {
                    throw new BusinessException(UserErrorCode.NOT_FOUND);
                }
            });
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
        AdminAuditLog.userSessionsEnded(actorId, userId);
    }

    // 로그인과 같은 방식(앞뒤 공백 제거·소문자)으로 맞춤. 빈 값은 조건이 없는 것으로 봄
    private static String normalizeEmail(String loginEmail) {
        return loginEmail == null || loginEmail.isBlank() ? null : loginEmail.strip().toLowerCase(Locale.ROOT);
    }

    // 요청 검사를 통과한 010으로 시작하는 11자리 번호를 저장 형식(010-0000-0000)으로 맞춤. 빈 값은 조건이 없는 것으로 봄
    private static String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        var digits = phone.replace("-", "");
        return digits.substring(0, 3) + "-" + digits.substring(3, 7) + "-" + digits.substring(7);
    }

    // 요청 시작 때 확인한 권한이 잠금을 기다리는 사이 바뀌었을 수 있어 잠근 행으로 다시 확인함
    private static void requireActiveMaster(User actor) {
        if (actor == null) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        actor.getStatus().requireActive();
        if (actor.getRole() != AuthRole.MASTER) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN);
        }
    }

    private record StatusChange(UserStatus before, UserStatus after) {
    }
}
