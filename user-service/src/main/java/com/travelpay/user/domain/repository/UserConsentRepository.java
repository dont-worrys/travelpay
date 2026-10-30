package com.travelpay.user.domain.repository;

import java.util.List;

import com.travelpay.user.domain.model.entity.UserConsent;

/** 약관 동의 기록의 저장 계약입니다. */
public interface UserConsentRepository {

    /**
     * 호출한 응용 서비스의 트랜잭션 안에서 새 동의 기록을 모두 추가합니다.
     * 기존 동의와 같은 키의 새 기록은 병합하지 않으며, 신규 저장 또는 DB 반영 시점에 실패합니다.
     */
    List<UserConsent> saveAll(List<UserConsent> consents);
}
