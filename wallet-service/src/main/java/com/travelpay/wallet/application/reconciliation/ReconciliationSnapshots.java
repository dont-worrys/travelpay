package com.travelpay.wallet.application.reconciliation;

import com.travelpay.wallet.application.reconciliation.dto.BankTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.ChargeFacts;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord;
import com.travelpay.wallet.application.reconciliation.dto.LedgerTransactionRecord.LedgerEntryRecord;
import com.travelpay.wallet.application.reconciliation.dto.SourceLookup;
import com.travelpay.wallet.domain.charge.ChargeRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 결과 스냅샷(JSON). 판정 시점에 확인한 사실과 충전에 저장된 ID를 함께 남긴다.
 * 소스마다 lookup(FOUND·NOT_FOUND·UNAVAILABLE)을 적어, 조회 실패와 기록 없음을 구분한다.
 * 계좌번호·예금주 등 개인 정보는 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class ReconciliationSnapshots {

    private final JsonMapper jsonMapper;

    public String ofCharge(ChargeRequest charge, ChargeFacts facts) {
        Map<String, Object> chargeNode = new LinkedHashMap<>();
        chargeNode.put("id", charge.getId());
        chargeNode.put("status", charge.getStatus().name());
        chargeNode.put("amount", charge.getAmount());
        chargeNode.put("currency", charge.getCurrency().name());
        chargeNode.put("walletId", charge.getWalletId());
        chargeNode.put("bankRequestId", charge.getBankRequestId());
        chargeNode.put("refundRequestId", charge.getRefundRequestId());
        chargeNode.put("bankTxId", charge.getBankTxId());
        chargeNode.put("refundBankTxId", charge.getRefundBankTxId());
        chargeNode.put("ledgerRef", charge.getLedgerRef());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("charge", chargeNode);
        root.put("bankDebit", bank(charge.getBankRequestId(), facts.debit()));
        root.put("bankRefund", bank(charge.getRefundRequestId(), facts.refund()));
        root.put("ledger", ledger(charge, facts.ledger()));
        return jsonMapper.writeValueAsString(root);
    }

    public String ofBankOrphan(BankTransactionRecord record) {
        Map<String, Object> node = bankRecord(record);
        node.put("type", record.type());
        return jsonMapper.writeValueAsString(Map.of("bank", node));
    }

    public String ofLedgerOrphan(LedgerTransactionRecord record) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("transactionId", record.transactionId());
        node.put("status", record.status());
        node.put("referenceId", record.referenceId());
        return jsonMapper.writeValueAsString(Map.of("ledger", node));
    }

    private static Map<String, Object> bank(String requestId, SourceLookup<BankTransactionRecord> lookup) {
        Map<String, Object> node = switch (lookup) {
            case SourceLookup.Found<BankTransactionRecord> found -> bankRecord(found.record());
            case SourceLookup.NotFound<BankTransactionRecord> ignored -> new LinkedHashMap<>();
            case SourceLookup.Unavailable<BankTransactionRecord> unavailable -> unavailable(unavailable);
        };
        node.putIfAbsent("requestId", requestId);
        node.put("lookup", lookupName(lookup));
        return node;
    }

    private static Map<String, Object> bankRecord(BankTransactionRecord record) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("requestId", record.requestId());
        node.put("bankTxId", record.bankTxId());
        node.put("status", record.status());
        node.put("amount", record.amount());
        return node;
    }

    private static Map<String, Object> ledger(ChargeRequest charge, SourceLookup<LedgerTransactionRecord> lookup) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("lookup", lookupName(lookup));
        switch (lookup) {
            case SourceLookup.Found<LedgerTransactionRecord> found -> {
                node.put("transactionId", found.record().transactionId());
                node.put("status", found.record().status());
                Optional<LedgerEntryRecord> entry = found.record().chargeInEntry(charge.getWalletId());
                node.put("amount", entry.map(LedgerEntryRecord::amount).orElse(null));
                node.put("currency", entry.map(LedgerEntryRecord::currency).orElse(null));
            }
            case SourceLookup.NotFound<LedgerTransactionRecord> ignored -> {
            }
            case SourceLookup.Unavailable<LedgerTransactionRecord> unavailable ->
                    node.put("reason", unavailable.reason());
        }
        return node;
    }

    private static Map<String, Object> unavailable(SourceLookup.Unavailable<?> unavailable) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("reason", unavailable.reason());
        return node;
    }

    private static String lookupName(SourceLookup<?> lookup) {
        return switch (lookup) {
            case SourceLookup.Found<?> ignored -> "FOUND";
            case SourceLookup.NotFound<?> ignored -> "NOT_FOUND";
            case SourceLookup.Unavailable<?> ignored -> "UNAVAILABLE";
        };
    }
}
