package com.harudle.auth.infrastructure.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class RefreshTokenHasher {

    private static final String HASH_ALGORITHM = "SHA-256";

    public String hash(String rawToken) {
        validateRawToken(rawToken);

        byte[] digest = createDigest(rawToken);

        return HexFormat.of().formatHex(digest);
    }

    private byte[] createDigest(String rawToken) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(HASH_ALGORITHM);
            return messageDigest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }

    private void validateRawToken(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken은 필수입니다.");

        if (rawToken.isBlank()) {
            throw new IllegalArgumentException("rawToken은 비어 있을 수 없습니다.");
        }
    }

}
