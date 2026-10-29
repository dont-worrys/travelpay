package com.travelpay.ledger.application.transaction;

import com.travelpay.ledger.application.transaction.dto.RegisterTransactionCommand;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 같은 reference의 재요청이 같은 내용인지 판별하는 요청 해시(SHA-256 hex, 64자).
 * 포함: referenceType, referenceId, originalTransactionId, 항목(ownerType, walletId 또는 accountType,
 * currency, direction, amount, entryType). 제외: description, userId.
 * 항목은 정규 문자열로 바꿔 정렬하므로 순서만 다른 요청은 같은 해시가 된다.
 * 각 값은 "길이:값"으로 적어 entryType 등에 구분자가 섞여도 다른 요청과 겹치지 않게 한다.
 */
final class LedgerRequestHasher {

    private static final String VERSION = "v1";

    private LedgerRequestHasher() {
    }

    static String hash(RegisterTransactionCommand command) {
        StringBuilder canonical = new StringBuilder(VERSION)
                .append(token(command.referenceType()))
                .append(token(command.referenceId()))
                .append(token(command.originalTransactionId()));
        command.entries().stream()
                .map(LedgerRequestHasher::canonicalEntry)
                .sorted()
                .forEach(canonical::append);
        return sha256(canonical.toString());
    }

    private static String canonicalEntry(RegisterTransactionCommand.Entry entry) {
        Object account = entry.walletId() != null ? entry.walletId() : entry.accountType();
        return token(token(entry.ownerType()) + token(account) + token(entry.currency())
                + token(entry.direction()) + token(entry.amount()) + token(entry.entryType()));
    }

    private static String token(Object value) {
        String text = Objects.toString(value, "");
        return text.length() + ":" + text;
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
