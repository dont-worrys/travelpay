package com.travelpay.payment.transfer.client;

import java.util.Optional;
import java.util.UUID;

import com.travelpay.common.error.BusinessException;
import com.travelpay.common.error.CommonErrorCode;
import com.travelpay.common.feign.FeignErrors;
import com.travelpay.common.response.ApiResponse;
import feign.FeignException;
import org.springframework.stereotype.Component;

/**
 * 수취인이 송금 받을 수 있는 회원(ACTIVE)인지 user-service에서 확인합니다.
 * USER_RECIPIENT_NOT_FOUND만 수취인 없음으로 보고, 그 밖의 실패는 common 규칙({@link FeignErrors#toCommonException})을 따릅니다.
 */
@Component
public class TransferUserReader {

    private static final String RECIPIENT_NOT_FOUND = "USER_RECIPIENT_NOT_FOUND";

    private final UserClient userClient;

    public TransferUserReader(UserClient userClient) {
        this.userClient = userClient;
    }

    /**
     * ACTIVE 회원이면 회원 정보를, 없는 회원이거나 정지·종료 회원이면 empty를 반환합니다(user-service가 둘을 구분하지 않음).
     * 경로 오류·배포 문제로 생기는 404는 수취인 없음이 아니므로 USER_RECIPIENT_NOT_FOUND일 때만 empty로 봅니다.
     * 다른 회원의 이름이 노출되지 않도록 응답의 회원 ID가 요청한 ID와 같은지 확인합니다.
     */
    public Optional<UserRecipientResponse> findRecipient(UUID userId) {
        ApiResponse<UserRecipientResponse> response;
        try {
            response = userClient.getRecipient(userId);
        } catch (FeignException exception) {
            if (FeignErrors.hasErrorCode(exception, RECIPIENT_NOT_FOUND)) {
                return Optional.empty();
            }
            throw FeignErrors.toCommonException(exception);
        }

        if (response == null || !response.success() || response.data() == null
                || !userId.equals(response.data().userId())
                || response.data().realName() == null || response.data().realName().isBlank()) {
            throw new BusinessException(CommonErrorCode.BAD_GATEWAY);
        }
        return Optional.of(response.data());
    }
}
