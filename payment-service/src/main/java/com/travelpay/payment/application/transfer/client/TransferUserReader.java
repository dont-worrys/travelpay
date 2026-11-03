package com.travelpay.payment.application.transfer.client;

import java.util.Optional;
import java.util.UUID;

/**
 * 수취인이 송금 받을 수 있는 회원(ACTIVE)인지 확인합니다.
 * 회원 서비스 장애는 common 오류(502·503·504)로 던집니다.
 */
public interface TransferUserReader {

    /** ACTIVE 회원이면 회원 정보를, 없는 회원이거나 정지·종료 회원이면 empty를 반환합니다(회원 서비스가 둘을 구분하지 않음). */
    Optional<TransferRecipient> findRecipient(UUID userId);
}
