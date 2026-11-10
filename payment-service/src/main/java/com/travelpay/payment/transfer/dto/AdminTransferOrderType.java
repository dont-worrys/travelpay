package com.travelpay.payment.transfer.dto;

/** 관리자 송금 목록 정렬입니다. 생성 시각이 같으면 ID로 같은 방향 정렬해 페이지 사이 순서를 고정합니다. */
public enum AdminTransferOrderType {
    /** 생성 시각 내림차순(기본) */
    LATEST,
    /** 생성 시각 오름차순 */
    OLDEST
}
