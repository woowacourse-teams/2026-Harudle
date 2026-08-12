package com.harudle.generation.service;

import com.harudle.generation.service.dto.GenerateDiaryImageCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public final class RequestFingerprintGenerator {

    private static final String FINGERPRINT_VERSION = "v1";
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final String CANONICAL_FIELD_SEPARATOR = "\n";

    RequestFingerprintGenerator() {
    }

    public String generate(GenerateDiaryImageCommand command) {
        validateCommand(command);
        String canonicalRequest = createCanonicalRequest(command);
        return hash(canonicalRequest);
    }

    private static String createCanonicalRequest(GenerateDiaryImageCommand command) {
        return String.join(
                CANONICAL_FIELD_SEPARATOR,
                FINGERPRINT_VERSION,
                command.userId().toString(),
                command.diaryDate().toString(),
                command.diaryText()
        );
    }

    private static String hash(String canonicalRequest) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] digest = messageDigest.digest(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 요청 지문 생성기를 초기화할 수 없습니다.",
                    exception
            );
        }
    }

    private static void validateCommand(GenerateDiaryImageCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("그림일기 이미지 생성 명령은 필수입니다.");
        }
    }
}
