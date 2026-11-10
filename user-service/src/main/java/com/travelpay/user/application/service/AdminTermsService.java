package com.travelpay.user.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.user.application.dto.AdminTermsListCommand;
import com.travelpay.user.application.dto.AdminTermsListResult;
import com.travelpay.user.application.dto.AdminTermsPublishCommand;
import com.travelpay.user.application.dto.TermsResult;
import com.travelpay.user.common.config.AdminProperties;
import com.travelpay.user.common.error.UserErrorCode;
import com.travelpay.user.domain.model.entity.TermsDocument;
import com.travelpay.user.domain.model.vo.TermsDocumentId;
import com.travelpay.user.domain.repository.TermsDocumentRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/** 관리자의 약관 버전 목록 조회와 새 버전 등록입니다. 게시한 버전은 고치거나 지우지 않고 새 버전만 추가합니다. */
@Service
@EnableConfigurationProperties(AdminProperties.class)
public class AdminTermsService {

    // 4자리 연도(9999년)까지만 받음. 그 뒤는 표기가 +10000-… 꼴로 바뀌고, PostgreSQL 범위(294276년)를 넘으면
    // 저장 장애(503)가 되므로 입력 오류(400)로 막음
    private static final Instant LATEST_EFFECTIVE_AT = Instant.parse("9999-12-31T23:59:59.999999Z");
    // 시행 시각이 커밋보다 먼저 오면 그 사이 가입한 회원이 이전 버전에 동의하게 됨. 관리자 트랜잭션 제한 시간(초 단위)보다 넉넉히 둠
    private static final Duration MINIMUM_LEAD = Duration.ofMinutes(1);

    private final TermsDocumentRepository termsDocumentRepository;
    private final Clock clock;
    private final TransactionTemplate readTransactions;
    private final TransactionTemplate writeTransactions;

    public AdminTermsService(TermsDocumentRepository termsDocumentRepository, Clock clock,
            PlatformTransactionManager transactionManager, AdminProperties properties) {
        this.termsDocumentRepository = termsDocumentRepository;
        this.clock = clock;
        var timeoutSeconds = Math.toIntExact(properties.transactionTimeout().getSeconds());
        this.readTransactions = new TransactionTemplate(transactionManager);
        this.readTransactions.setReadOnly(true);
        this.readTransactions.setTimeout(timeoutSeconds);
        this.writeTransactions = new TransactionTemplate(transactionManager);
        this.writeTransactions.setTimeout(timeoutSeconds);
    }

    /** 약관 목록 조회: 약관 코드 순, 같은 코드 안에서는 시행 시각이 최신인 버전부터 한 페이지를 돌려줍니다. */
    public AdminTermsListResult list(AdminTermsListCommand command) {
        var offset = (long) command.page() * command.size();
        if (offset > Integer.MAX_VALUE) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        var termsCode = command.termsCode() == null || command.termsCode().isBlank()
                ? null : command.termsCode().strip();
        try {
            return Objects.requireNonNull(readTransactions.execute(status -> {
                // 다음 페이지가 있는지 알려고 한 개 더 읽음. 전체 개수는 세지 않음
                var found = termsDocumentRepository.findPage(termsCode, (int) offset, command.size() + 1);
                var hasNext = found.size() > command.size();
                var items = (hasNext ? found.subList(0, command.size()) : found).stream()
                        .map(TermsResult::from).toList();
                return new AdminTermsListResult(items, command.page(), command.size(), hasNext);
            }));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
    }

    /**
     * 새 약관 버전 등록: 새 버전을 추가만 합니다. 시행 시각은 등록 시점보다 1분 이상 뒤여야 합니다.
     * 지난 시각을 허용하면 그 사이 가입한 회원이 동의한 버전과 그때 시행 중이던 버전이 어긋나기 때문입니다.
     */
    public TermsResult publish(UUID actorId, AdminTermsPublishCommand command) {
        // DB가 마이크로초까지 저장하므로, 응답과 저장값이 같도록 미리 맞춤
        var effectiveAt = command.effectiveAt().truncatedTo(ChronoUnit.MICROS);
        if (effectiveAt.isBefore(clock.instant().plus(MINIMUM_LEAD))) {
            throw new BusinessException(UserErrorCode.TERMS_EFFECTIVE_AT_NOT_FUTURE);
        }
        if (effectiveAt.isAfter(LATEST_EFFECTIVE_AT)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST);
        }
        TermsDocument document;
        try {
            document = TermsDocument.publish(new TermsDocumentId(command.termsCode(), command.termsVersion()),
                    command.title(), command.content(), command.required(), effectiveAt);
        }
        catch (IllegalArgumentException exception) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST, exception);
        }
        TermsResult published;
        try {
            published = Objects.requireNonNull(writeTransactions.execute(status -> {
                termsDocumentRepository.insert(document);
                return TermsResult.from(document);
            }));
        }
        catch (DataAccessException | TransactionException exception) {
            throw new BusinessException(CommonErrorCode.SERVICE_UNAVAILABLE, exception);
        }
        AdminAuditLog.termsPublished(actorId, published.termsCode(), published.termsVersion(), published.effectiveAt());
        return published;
    }
}
