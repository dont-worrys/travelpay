package com.travelpay.user.application.dto;

import java.util.List;
import java.util.UUID;

import com.travelpay.common.auth.AuthRole;
import com.travelpay.user.domain.model.vo.UserStatus;
import com.travelpay.user.domain.repository.UserSearchItem;

/** 관리자 회원 검색 결과의 한 페이지입니다. 전체 개수 대신 다음 페이지가 있는지만 알려 줍니다. */
public record AdminUserSearchResult(List<Member> items, int page, int size, boolean hasNext) {

    /** 검색된 회원 한 명입니다. 비밀번호 로그인 정보가 없는 회원은 loginEmail이 null입니다. */
    public record Member(UUID userId, String realName, String loginEmail, String phone, UserStatus status,
            AuthRole role) {

        public static Member from(UserSearchItem item) {
            return new Member(item.userId(), item.realName(), item.loginEmail(), item.phone(), item.status(),
                    item.role());
        }
    }
}
