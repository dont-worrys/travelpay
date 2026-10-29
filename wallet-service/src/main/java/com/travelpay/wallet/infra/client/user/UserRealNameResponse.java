package com.travelpay.wallet.infra.client.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** toString에 실명이 찍히지 않도록 재정의한다 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserRealNameResponse(String realName) {

    @Override
    public String toString() {
        return "UserRealNameResponse[realName=***]";
    }
}
