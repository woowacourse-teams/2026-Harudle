package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DiarySourceTextLoadTestFixtureMainTest {

    private static final String PREFIX = "harudle.diary.source-text-encryption";
    private static final String TEXT = "부하 테스트 준비 데이터";
    private static final UUID DIARY = UUID.fromString("f456822f-14b0-468e-82c5-d4c4c92dce5c");
    private static final UUID USER = UUID.fromString("2a705b0a-2123-4363-8365-7f8c37f054ed");
    private static final UUID IDEMPOTENCY = UUID.fromString("62334bed-dcd2-4bf2-ae39-a5b748683ce0");
    private static final LocalDate DATE = LocalDate.of(2026, 10, 1);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path directory;

    private DiarySourceTextEncryptionProperties keys;
    private Map<String, Object> raw;
    private Map<String, Object> properties;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        keys = new DiarySourceTextEncryptionProperties("primary", Map.of("primary", key));
        Path verification = directory.resolve("checks.json");
        new DiarySourceTextKeyVerification(keys).createInitialEvidence(verification, "dev");
        raw = new HashMap<>(Map.of(
                "DEPLOY_ENV", "dev", "DB_NAME", "harudle_dev", "S3_BUCKET", "techcourse-project-2026",
                "S3_GENERATED_PREFIX", "harudle/generated/diary-images/dev",
                "DIARY_SOURCE_TEXT_RELEASE_MODE", "writer"
        ));
        properties = new HashMap<>(Map.of(
                "spring.config.location", "optional:classpath:/missing-load-test-fixture.yml",
                PREFIX + ".active-key-id", "primary", PREFIX + ".keys.primary", key,
                PREFIX + ".verification-file", verification.toString(), PREFIX + ".new-writes-enabled", "true"
        ));
        output = new ByteArrayOutputStream();
    }

    @Test
    @DisplayName("고정 합성 일기를 실제 앱 암호화와 요청 지문 생성기로 보호하고 제한한 필드만 출력한다")
    void encryptFixedSyntheticTextWithApplicationCore() {
        assertThat(execute(input())).isZero();
        JsonNode row = JSON.readTree(output.toByteArray()).get(0);
        assertThat(row.size()).isEqualTo(3);
        assertThat(row.get("diaryId").asString()).isEqualTo(DIARY.toString());
        String encrypted = row.get("sourceText").asString();
        String protectedFingerprint = row.get("requestFingerprint").asString();
        DiarySourceTextCipher cipher = new AesGcmDiarySourceTextCipher(keys);
        assertThat(cipher.decrypt(DIARY, USER, encrypted, 1)).isEqualTo(TEXT);
        String rawFingerprint = new RequestFingerprintGenerator().generate(
                new GenerateDiaryImageCommand(USER, DIARY, DATE, TEXT, IDEMPOTENCY)
        );
        assertThat(protectedFingerprint).isEqualTo(
                cipher.protectRequestFingerprint(DIARY, USER, encrypted, 1, rawFingerprint)
        ).isNotEqualTo(rawFingerprint);
        assertThat(output.toString(StandardCharsets.UTF_8)).doesNotContain(TEXT, rawFingerprint);
    }

    @Test
    @DisplayName("같은 합성 일기를 다시 준비해도 암호문 nonce는 새로 생성한다")
    void encryptAgainWithFreshNonce() {
        assertThat(execute(input())).isZero();
        String first = JSON.readTree(output.toByteArray()).get(0).get("sourceText").asString();
        output.reset();
        assertThat(execute(input())).isZero();
        assertThat(JSON.readTree(output.toByteArray()).get(0).get("sourceText").asString()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("상위 설정으로 위장해도 실제 prod 환경과 다른 DB 또는 S3 대상은 거절한다")
    void rejectRawEnvironmentOutsideDevTarget() {
        for (String field : new String[]{"DEPLOY_ENV", "DB_NAME", "S3_BUCKET", "S3_GENERATED_PREFIX"}) {
            Object previous = raw.put(field, "production-secret");
            properties.put(field, previous);
            assertRejected(input());
            raw.put(field, previous);
        }
    }

    @Test
    @DisplayName("reader 정책이나 기록한 키 검증 자료와 다른 설정이면 암호화 결과를 만들지 않는다")
    void rejectReaderOrUnverifiedKeys() {
        raw.put("DIARY_SOURCE_TEXT_RELEASE_MODE", "reader");
        properties.put(PREFIX + ".new-writes-enabled", "false");
        assertRejected(input());
        raw.put("DIARY_SOURCE_TEXT_RELEASE_MODE", "writer");
        properties.put(PREFIX + ".new-writes-enabled", "true");
        properties.put(PREFIX + ".keys.primary", Base64.getEncoder().encodeToString(new byte[31]));
        assertRejected(input());
        properties.put(PREFIX + ".keys.primary", Base64.getEncoder().encodeToString(
                "a".repeat(32).getBytes(StandardCharsets.US_ASCII)
        ));
        assertRejected(input());
        properties.put(PREFIX + ".keys.primary", Base64.getEncoder().encodeToString(new byte[32]));
        properties.put(PREFIX + ".verification-file", directory.resolve("missing.json").toString());
        assertRejected(input());
    }

    @Test
    @DisplayName("원문 입력과 중복 필드 또는 식별자 및 잘못된 JSON은 출력 없이 거절한다")
    void rejectUnexpectedOrMalformedInput() {
        String row = input().substring(1, input().length() - 1);
        for (String value : new String[]{
                "[]", "{}", "not-json", input() + "[]", "[" + row + "," + row + "]",
                input().replace("\"diaryDate\"", "\"sourceText\":\"private text\",\"diaryDate\""),
                input().replace("\"diaryId\"", "\"diaryId\":\"private text\",\"diaryId\""),
                input().replace(DIARY.toString(), "not-a-uuid"), input().replace("2026-10-01", "2026-02-30")
        }) {
            assertRejected(value);
        }
    }

    @Test
    @DisplayName("입력 바이트와 행 수가 제한을 넘으면 전체 결과를 거절한다")
    void rejectUnboundedInput() {
        assertRejected(" ".repeat(262_145));
        String row = input().substring(1, input().length() - 1);
        assertRejected("[" + String.join(",", java.util.Collections.nCopies(1_001, row)) + "]");
    }

    private String input() {
        return """
                [{"diaryId":"%s","userId":"%s","diaryDate":"%s","idempotencyKey":"%s"}]
                """.formatted(DIARY, USER, DATE, IDEMPOTENCY).strip();
    }

    private int execute(String input) {
        return DiarySourceTextLoadTestFixtureMain.execute(new String[]{}, new TestEnvironment(raw, properties),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(output));
    }

    private void assertRejected(String input) {
        output.reset();
        assertThat(execute(input)).isEqualTo(1);
        assertThat(output.toByteArray()).isEmpty();
    }

    private static final class TestEnvironment extends StandardEnvironment {

        private Map<String, Object> raw;

        private TestEnvironment(Map<String, Object> raw, Map<String, Object> properties) {
            this.raw = raw;
            getPropertySources().replace(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new SystemEnvironmentPropertySource(SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, raw));
            getPropertySources().addFirst(new MapPropertySource("fixture-test", properties));
        }

        @Override
        public Map<String, Object> getSystemEnvironment() {
            return raw == null ? Map.of() : raw;
        }
    }
}
