package com.travelpay.user.presentation.dto;

import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.application.dto.AdminUserSearchResult;
import com.travelpay.user.domain.model.vo.UserStatus;

/** 관리자 회원 검색 응답입니다. 성명·이메일·휴대폰 번호는 가려서 돌려줍니다. */
public record AdminUserSearchResponse(List<Item> items, int page, int size, boolean hasNext) {

    public static AdminUserSearchResponse from(AdminUserSearchResult result) {
        return new AdminUserSearchResponse(result.items().stream().map(Item::from).toList(),
                result.page(), result.size(), result.hasNext());
    }

    public record Item(UUID userId, String maskedRealName, String maskedEmail, String maskedPhone, UserStatus status,
            AuthRole role) {

        static Item from(AdminUserSearchResult.Member member) {
            return new Item(member.userId(), PersonalInfoMasking.realName(member.realName()),
                    PersonalInfoMasking.email(member.loginEmail()), PersonalInfoMasking.phone(member.phone()),
                    member.status(), member.role());
        }
    }
}
