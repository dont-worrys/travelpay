package com.travelpay.payment.transfer.application;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TransferRequestHasherTest {

    private static final UUID RECEIVER_ID = UUID.fromString("4c6a0178-266b-4f1e-b811-a2767026be70");

    @Test
    void producesStableSha256HexForSameRequest() {
        String hash = TransferRequestHasher.hash(RECEIVER_ID, 10_000L, "KRW", "저녁값");

        assertEquals(64, hash.length());
        assertEquals(hash, TransferRequestHasher.hash(RECEIVER_ID, 10_000L, "KRW", "저녁값"));
    }

    @Test
    void distinguishesEachRequestField() {
        String base = TransferRequestHasher.hash(RECEIVER_ID, 10_000L, "KRW", null);

        assertNotEquals(base, TransferRequestHasher.hash(UUID.randomUUID(), 10_000L, "KRW", null));
        assertNotEquals(base, TransferRequestHasher.hash(RECEIVER_ID, 10_001L, "KRW", null));
        assertNotEquals(base, TransferRequestHasher.hash(RECEIVER_ID, 10_000L, "JPY", null));
        assertNotEquals(base, TransferRequestHasher.hash(RECEIVER_ID, 10_000L, "KRW", "메모"));
    }
}
