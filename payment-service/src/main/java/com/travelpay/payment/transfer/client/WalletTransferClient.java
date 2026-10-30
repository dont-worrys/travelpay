package com.travelpay.payment.transfer.client;

/**
 * 송금인 지갑 차감과 수취인 지갑 증가를 함께 처리하는 지갑 송금 처리 인터페이스입니다.
 * 실제 구현은 아직 없으며(Wallet/Ledger 역할 분담 확정 전), 구현 bean이 없으면 송금은 접수(REQUESTED)까지만 처리합니다.
 *
 * <p>구현체는 다음을 보장해야 합니다.
 * <ul>
 *     <li>송금 ID(reference)가 같은 요청은 한 번만 처리하고, 재요청에는 처음 처리 결과를 반환합니다.
 *         송금 서비스는 결과를 모를 때 같은 요청을 재전송하므로, 이 보장이 없으면 잔액이 중복 변경됩니다.</li>
 *     <li>차감과 증가는 함께 반영되거나 함께 반영되지 않아야 합니다.</li>
 * </ul>
 * 타임아웃 등으로 결과를 알 수 없으면 {@link WalletTransferResult#UNKNOWN}을 반환하거나 예외를 던질 수 있으며,
 * 송금 서비스는 둘 다 결과 모름으로 처리합니다.
 */
public interface WalletTransferClient {

    WalletTransferResult transfer(WalletTransferRequest request);
}
