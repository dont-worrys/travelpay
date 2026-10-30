package com.travelpay.wallet.infra.crypto;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AesGcmAccountNumberCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private final AesGcmAccountNumberCipher cipher = new AesGcmAccountNumberCipher(KEY);

    @Test
    void 암호화한_값을_복호화하면_원래_계좌번호가_나온다() {
        String encrypted = cipher.encrypt("1234567890123");

        assertTrue(encrypted.startsWith("v1:"));
        assertFalse(encrypted.contains("1234567890123"));
        assertEquals("1234567890123", cipher.decrypt(encrypted));
    }

    @Test
    void 같은_평문도_매번_다른_암호문이_나온다() {
        assertNotEquals(cipher.encrypt("1234567890"), cipher.encrypt("1234567890"));
    }

    @Test
    void 변조된_암호문은_복호화하지_않는다() {
        String encrypted = cipher.encrypt("1234567890");
        byte[] raw = Base64.getDecoder().decode(encrypted.substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void 다른_키로_만든_암호문은_복호화하지_않는다() {
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        String encrypted = new AesGcmAccountNumberCipher(Base64.getEncoder().encodeToString(otherKey))
                .encrypt("1234567890");

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(encrypted));
    }

    @Test
    void 형식이_다른_암호문은_거절한다() {
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("1234567890"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("v1:!!!"));
        assertThrows(IllegalStateException.class, () -> cipher.decrypt("v1:AAAA"));
    }

    @Test
    void 키가_없거나_32바이트가_아니면_만들지_않는다() {
        assertThrows(IllegalStateException.class, () -> new AesGcmAccountNumberCipher(""));
        assertThrows(IllegalStateException.class, () -> new AesGcmAccountNumberCipher("not-base64!"));
        assertThrows(IllegalStateException.class,
                () -> new AesGcmAccountNumberCipher(Base64.getEncoder().encodeToString(new byte[16])));
    }
}
