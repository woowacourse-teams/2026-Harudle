package com.harudle.auth.infrastructure.oauth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.util.StringUtils;

public final class AppleTokenCipher {

    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AppleTokenCipher(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("애플 토큰 암호화 키는 Base64 형식이어야 합니다.");
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("애플 토큰 암호화 키는 32바이트여야 합니다.");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    public String encrypt(String token) {
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException("암호화할 애플 토큰이 필요합니다.");
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("애플 토큰을 암호화할 수 없습니다.", exception);
        }
    }

    public String decrypt(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length < IV_BYTES + TAG_BITS / 8) {
                throw new IllegalArgumentException();
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("애플 토큰을 복호화할 수 없습니다.", exception);
        }
    }
}
