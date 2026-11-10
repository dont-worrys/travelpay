package com.travelpay.wallet.infra.client.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/** 충전 예금주 확인용 회원 정보. toString에 실명이 찍히지 않도록 재정의한다 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserFundingIdentityResponse(UUID userId, String realName) {

    @Override
    public String toString() {
        return "UserFundingIdentityResponse[userId=" + userId + ", realName=***]";
    }
}
