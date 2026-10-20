package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.service.DiarySourceTextWritePolicyValidator;
import com.harudle.diary.service.DiarySourceTextWriteProperties;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Encrypts only a fixed synthetic fixture in a dev writer, using its verified application key ring.
 * Does not connect to a database or start the web app. Output contains IDs, ciphertext and protected fingerprints only.
 */
public final class DiarySourceTextLoadTestFixtureMain {

    private static final String PREFIX = "harudle.diary.source-text-encryption";
    private static final String FIXTURE_TEXT = "부하 테스트 준비 데이터";
    private static final int MAX_INPUT_BYTES = 262_144;
    private static final int MAX_ROWS = 1_000;
    private static final Set<String> INPUT_FIELDS = Set.of("diaryId", "userId", "diaryDate", "idempotencyKey");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private DiarySourceTextLoadTestFixtureMain() {
    }

    public static void main(String[] args) {
        int status = execute(args, new StandardEnvironment(), System.in, System.out);
        if (status != 0) {
            System.err.println("Load test fixture encryption failed.");
        }
        System.exit(status);
    }

    static int execute(String[] args, ConfigurableEnvironment environment, InputStream input, PrintStream output) {
        try {
            require(args.length == 0);
            requireDevTarget(environment);
            DiarySourceTextKeyCheckMain.prepareEnvironment(environment);
            DiarySourceTextCipher cipher = verifiedCipher(environment);
            List<FixtureInput> rows = readInput(input);
            output.println(JSON.writeValueAsString(encrypt(rows, cipher)));
            return output.checkError() ? 1 : 0;
        } catch (IOException | RuntimeException exception) {
            return 1;
        }
    }

    private static void requireDevTarget(ConfigurableEnvironment environment) {
        Map<String, Object> raw = environment.getSystemEnvironment();
        require("dev".equals(raw.get("DEPLOY_ENV")) && "harudle_dev".equals(raw.get("DB_NAME")));
        require("techcourse-project-2026".equals(raw.get("S3_BUCKET"))
                && "harudle/generated/diary-images/dev".equals(raw.get("S3_GENERATED_PREFIX")));
    }

    private static DiarySourceTextCipher verifiedCipher(ConfigurableEnvironment environment) {
        Binder binder = Binder.get(environment);
        DiarySourceTextEncryptionProperties keys = binder
                .bind(PREFIX, Bindable.of(DiarySourceTextEncryptionProperties.class))
                .orElseThrow(DiarySourceTextCryptoException::new);
        DiarySourceTextWriteProperties policy = binder.bind(PREFIX,
                Bindable.of(DiarySourceTextWriteProperties.class)).orElseThrow(DiarySourceTextCryptoException::new);
        DiarySourceTextWritePolicyValidator.validate(policy, environment);
        require(policy.newWritesEnabled());
        new DiarySourceTextKeyVerification(keys).verify(
                Path.of(environment.getRequiredProperty(PREFIX + ".verification-file")), "dev"
        );
        return new AesGcmDiarySourceTextCipher(keys);
    }

    private static List<FixtureInput> readInput(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_INPUT_BYTES + 1);
        require(bytes.length > 0 && bytes.length <= MAX_INPUT_BYTES);
        JsonNode root = JSON.readTree(bytes);
        require(root != null && root.isArray() && !root.isEmpty() && root.size() <= MAX_ROWS);
        List<FixtureInput> rows = new ArrayList<>();
        Set<UUID> ids = new HashSet<>();
        for (JsonNode node : root) {
            FixtureInput row = readRow(node);
            require(ids.add(row.diaryId()));
            rows.add(row);
        }
        return rows;
    }

    private static FixtureInput readRow(JsonNode node) {
        require(node.isObject() && node.size() == INPUT_FIELDS.size());
        require(node.properties().stream().allMatch(entry -> INPUT_FIELDS.contains(entry.getKey())));
        UUID diaryId = identifier(node, "diaryId");
        UUID userId = identifier(node, "userId");
        UUID idempotencyKey = identifier(node, "idempotencyKey");
        String date = text(node, "diaryDate");
        LocalDate diaryDate = LocalDate.parse(date);
        require(diaryDate.toString().equals(date));
        return new FixtureInput(diaryId, userId, diaryDate, idempotencyKey);
    }

    private static UUID identifier(JsonNode node, String field) {
        String value = text(node, field);
        UUID id = UUID.fromString(value);
        require(id.toString().equals(value));
        return id;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        require(value != null && value.isString());
        return value.asString();
    }

    private static List<FixtureOutput> encrypt(List<FixtureInput> rows, DiarySourceTextCipher cipher) {
        RequestFingerprintGenerator fingerprints = new RequestFingerprintGenerator();
        List<FixtureOutput> result = new ArrayList<>();
        for (FixtureInput row : rows) {
            String encrypted = cipher.encrypt(row.diaryId(), row.userId(), FIXTURE_TEXT);
            String raw = fingerprints.generate(new GenerateDiaryImageCommand(
                    row.userId(), row.diaryId(), row.diaryDate(), FIXTURE_TEXT, row.idempotencyKey()
            ));
            String protectedFingerprint = cipher.protectRequestFingerprint(row.diaryId(), row.userId(), encrypted,
                    DiarySourceTextCipher.CURRENT_VERSION, raw);
            result.add(new FixtureOutput(row.diaryId(), encrypted, protectedFingerprint));
        }
        return result;
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new DiarySourceTextCryptoException();
        }
    }

    private record FixtureInput(UUID diaryId, UUID userId, LocalDate diaryDate, UUID idempotencyKey) {
    }

    private record FixtureOutput(UUID diaryId, String sourceText, String requestFingerprint) {

        @Override
        public String toString() {
            return "FixtureOutput[redacted]";
        }
    }
}
