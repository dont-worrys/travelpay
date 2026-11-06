package com.travelpay.wallet.application.resolution;

/** 조건부 UPDATE가 0건 (충전이 이미 MANUAL_REVIEW가 아님). 트랜잭션을 롤백시키고 서비스가 멱등 키로 다시 조회한다 */
class ResolutionNotAppliedException extends RuntimeException {

    ResolutionNotAppliedException() {
        super("충전이 이미 MANUAL_REVIEW가 아니어서 보정을 반영하지 않았습니다.");
    }
}
