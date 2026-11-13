package com.travelpay.payment.transfer.client;

/**
 * 송금을 원장 거래로 등록하는 인터페이스입니다. 구현은 원장 {@code POST /internal/v1/ledger/transactions}를 호출하는
 * {@link TransferLedgerClientAdapter}입니다.
 *
 * <p>구현체는 다음을 보장해야 합니다. 현재 ledger-service 코드 기준이며 팀 확정 전입니다.
 * <ul>
 *     <li>reference(TRANSFER + 송금 ID)가 같은 요청은 한 번만 처리하고, 재요청에는 처음 처리 결과(거절 포함)를 반환합니다.
 *         송금 서비스는 결과를 모를 때 같은 요청을 재전송하므로, 이 보장이 없으면 잔액이 중복 변경됩니다.</li>
 *     <li>송금인 차감과 수취인 증가는 함께 반영되거나 함께 반영되지 않아야 합니다.</li>
 *     <li>확정 거절(원장 422)은 {@link LedgerTransactionResponse.Status#REJECTED}와 사유로 반환합니다.</li>
 *     <li>타임아웃·연결 실패·5xx처럼 원장 처리 여부를 알 수 없으면 예외를 던집니다. 송금 서비스는 결과 모름으로 처리합니다.
 *         확정 거절이 아닌 4xx(409·400 등)도 예외({@link TransferLedgerException})로 던지며, 송금은 PROCESSING으로 남습니다.</li>
 * </ul>
 */
public interface TransferLedgerClient {

    LedgerTransactionResponse register(LedgerTransactionRequest request);
}
