package com.travelpay.payment.transfer.client;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.travelpay.payment.transfer.domain.Transfer;

/**
 * 원장에 기록된 송금 거래입니다(reference 조회 결과). 거래 결과와 원장에 실제로 기록된 항목을 함께 담습니다.
 * POSTED 거래는 항목이 있고, REJECTED 거래는 원장이 항목을 남기지 않아 빈 목록입니다.
 * 항목은 원장 등록 요청과 같은 형식({@link LedgerTransactionRequest.Entry})으로 두어 송금 값으로 만든 요청과 바로 비교합니다.
 */
public record LedgerTransferRecord(LedgerTransactionResponse result, List<LedgerTransactionRequest.Entry> entries) {

    public LedgerTransferRecord {
        entries = List.copyOf(entries);
    }

    /**
     * 원장에 기록된 항목이 이 송금으로 만든 원장 요청 항목(송금인 지갑 DEBIT TRANSFER_OUT, 수취인 지갑 CREDIT TRANSFER_IN,
     * 같은 통화·금액)과 같은지 확인합니다. 순서는 보지 않고 항목 구성(개수 포함)만 비교합니다.
     * 항목이 없는 거래(REJECTED)는 내용을 확인할 수 없어 false입니다.
     */
    public boolean matches(Transfer transfer) {
        if (entries.isEmpty()) {
            return false;
        }
        return count(entries).equals(count(LedgerTransactionRequest.of(transfer).entries()));
    }

    private static Map<LedgerTransactionRequest.Entry, Long> count(List<LedgerTransactionRequest.Entry> entries) {
        return entries.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
