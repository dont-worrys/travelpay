package com.travelpay.wallet.application.charge;

import java.util.Set;

/**
 * 은행 코드별로 지금 호출을 보내지 않는 은행. 복구 스케줄러가 조회 직전에 제외 목록으로 쓴다.
 * 반열림 은행은 포함하지 않는다 (복구 대상이 시험 호출로 나가야 닫힐 수 있으므로)
 */
public interface BankAvailability {

    /** 서킷이 열려 있어 호출을 보내지 않는 은행 코드 */
    Set<String> openBankCodes();
}
