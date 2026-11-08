package com.travelpay.user.presentation.dto;

import com.travelpay.user.domain.model.vo.UserStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** 관리자 회원 정지·해제 요청입니다. 관리자는 활동(ACTIVE)과 정지(SUSPENDED)만 지정할 수 있습니다. */
public record AdminUserStatusRequest(
        @NotNull(message = "변경할 상태를 입력해 주세요.")
        @Pattern(regexp = "ACTIVE|SUSPENDED", message = "상태는 ACTIVE 또는 SUSPENDED만 지정할 수 있습니다.")
        String status
) {

    public UserStatus toStatus() {
        return UserStatus.valueOf(status);
    }
}
