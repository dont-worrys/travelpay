package com.travelpay.payment.transfer.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * 같은 Idempotency-Key의 재요청이 처음 요청과 같은 내용인지 비교하기 위한 SHA-256 값을 만듭니다.
 * 정규화한 값으로 계산하므로 JSON 필드 순서·공백 차이는 같은 요청으로 봅니다.
 */
final class TransferRequestHasher {

    private TransferRequestHasher() {
    }

    /** memo는 정규화된 값(앞뒤 공백 제거, 빈 값은 null)을 전달해야 합니다. */
    static String hash(UUID receiverId, long amount, String currency, String memo) {
        Objects.requireNonNull(receiverId, "receiverId");
        Objects.requireNonNull(currency, "currency");

        // memo는 형식 제한이 없어 구분자가 들어갈 수 있으므로 마지막 필드로 둡니다.
        String canonical = "receiverId=" + receiverId
                + "\namount=" + amount
                + "\ncurrency=" + currency
                + "\nmemo=" + (memo == null ? "" : memo);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
