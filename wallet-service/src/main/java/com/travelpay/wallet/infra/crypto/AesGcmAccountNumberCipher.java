package com.travelpay.wallet.infra.crypto;

import com.travelpay.wallet.domain.charge.AccountNumberCipher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

/**
 * AES-256-GCM 계좌번호 암호화. 저장 형식: "v1:" + Base64(IV 12바이트 + 암호문·태그).
 * 버전 접두사는 이후 키 교체 때 어느 키로 암호화했는지 구분하기 위해 둔다.
 * 키·평문은 예외 메시지에 넣지 않는다.
 */
@Component
public class AesGcmAccountNumberCipher implements AccountNumberCipher {

    private static final String VERSION_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmAccountNumberCipher(@Value("${travelpay.wallet.account-number-key}") String base64Key) {
        this.key = new SecretKeySpec(decodeKey(base64Key), "AES");
    }

    @Override
    public String encrypt(String accountNumber) {
        Objects.requireNonNull(accountNumber, "accountNumber");
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(accountNumber.getBytes(StandardCharsets.UTF_8));
            byte[] stored = ByteBuffer.allocate(IV_BYTES + encrypted.length).put(iv).put(encrypted).array();
            return VERSION_PREFIX + Base64.getEncoder().encodeToString(stored);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("계좌번호를 암호화하지 못했습니다.", e);
        }
    }

    @Override
    public String decrypt(String accountNumberEnc) {
        Objects.requireNonNull(accountNumberEnc, "accountNumberEnc");
        if (!accountNumberEnc.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException("지원하지 않는 계좌번호 암호문 형식입니다.");
        }
        try {
            byte[] stored = Base64.getDecoder().decode(accountNumberEnc.substring(VERSION_PREFIX.length()));
            if (stored.length <= IV_BYTES + TAG_BITS / 8) {
                throw new IllegalStateException("계좌번호 암호문 길이가 올바르지 않습니다.");
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(stored, IV_BYTES, stored.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("계좌번호를 복호화하지 못했습니다.", e);
        }
    }

    /** 키가 없거나 형식이 틀리면 기동을 멈춘다. 예외 메시지에 키 값을 넣지 않는다 */
    private static byte[] decodeKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException("계좌번호 암호화 키가 설정되지 않았습니다.");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("계좌번호 암호화 키는 Base64 형식이어야 합니다.");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalStateException("계좌번호 암호화 키는 32바이트여야 합니다.");
        }
        return decoded;
    }
}
