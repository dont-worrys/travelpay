package com.travelpay.user.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.travelpay.user.domain.model.entity.User;

/** 회원 공통 정보의 저장·조회 계약입니다. */
public interface UserRepository {

    /** 회원을 저장하며, 휴대폰 번호 중복은 User의 업무 예외로 거절합니다. */
    User save(User user);

    /** 회원 ID로 조회하며, 존재하지 않으면 빈 결과를 반환합니다. */
    Optional<User> findById(UUID userId);

    /** 해당 휴대폰 번호로 등록된 회원이 있는지 확인합니다. */
    boolean existsByPhone(String phone);

    /** 회원의 세션 버전을 1 올립니다. 이미 읽어 둔 User의 sessionVersion은 갱신되지 않으며, 회원이 없으면 false입니다. */
    boolean increaseSessionVersion(UUID userId);

    /** 회원들을 ID 순서로 잠가 조회합니다. 트랜잭션 안에서 불러야 하고, 잠금은 그 트랜잭션이 끝날 때 풀립니다. 없는 ID는 결과에서 빠집니다. */
    List<User> findAllByIdForUpdate(Collection<UUID> userIds);

    /** 관리자 회원 검색입니다. 가입 최신순·ID 역순으로 정렬해 offset부터 최대 limit개를 돌려줍니다. */
    List<UserSearchItem> search(UserSearchCondition condition, int offset, int limit);
}
