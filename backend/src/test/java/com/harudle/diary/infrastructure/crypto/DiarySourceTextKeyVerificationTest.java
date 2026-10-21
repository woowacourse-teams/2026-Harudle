package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DiarySourceTextKeyVerificationTest {

    private static final String OLD_KEY = encodedKey((byte) 1);
    private static final String NEW_KEY = encodedKey((byte) 2);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    private Path directory;

    @Test
    @DisplayName("처음 등록한 환경별 검증 자료로 암호화 키와 HMAC 지문을 검증한다")
    void initializeAndVerifyPublicCanaries() throws IOException {
        Path file = file();
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));

        verification.createInitialEvidence(file, "dev");
        verification.verify(file, "dev");

        Map<String, Object> manifest = read(file);
        assertThat(manifest).containsOnlyKeys("formatVersion", "environment", "checks")
                .containsEntry("formatVersion", 1).containsEntry("environment", "dev");
        assertThat(checks(manifest)).containsOnlyKeys("old");
        assertThat(check(manifest, "old")).containsOnlyKeys("ciphertext", "protectedFingerprint");
        assertThat(Files.readString(file)).doesNotContain(OLD_KEY, "harudle diary source text key verification");
        assertThat(Files.getPosixFilePermissions(file))
                .isEqualTo(PosixFilePermissions.fromString("rw-------"));
    }

    @Test
    @DisplayName("일반 검증은 읽기 전용 파일을 변경하거나 잠금 파일을 만들지 않는다")
    void verifyIsReadOnly() throws IOException {
        Path file = file();
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));
        verification.createInitialEvidence(file, "dev");
        Files.delete(lockFile());
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r--------"));
        byte[] original = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);

        verification.verify(file, "dev");

        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        assertThat(lockFile()).doesNotExist();
    }

    @Test
    @DisplayName("같은 키 ID에 다른 32바이트 키를 넣으면 검증과 추가 등록이 모두 실패한다")
    void rejectChangedMaterialUnderExistingKeyId() throws IOException {
        Path file = file();
        verifier("old", Map.of("old", OLD_KEY)).createInitialEvidence(file, "dev");
        byte[] original = Files.readAllBytes(file);
        DiarySourceTextKeyVerification wrong = verifier("old", Map.of("old", NEW_KEY));

        assertCryptoFailure(() -> wrong.verify(file, "dev"));
        assertCryptoFailure(() -> wrong.register(file, "dev"));

        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    @Test
    @DisplayName("초기화는 이미 존재하는 검증 파일을 덮어쓰지 않는다")
    void initializeNeverOverwritesExistingEvidence() throws IOException {
        Path file = file();
        verifier("old", Map.of("old", OLD_KEY)).createInitialEvidence(file, "dev");
        byte[] original = Files.readAllBytes(file);

        assertCryptoFailure(() -> verifier("old", Map.of("old", NEW_KEY)).createInitialEvidence(file, "dev"));

        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    @Test
    @DisplayName("검증 자료가 없으면 검증과 추가 등록은 자동 생성하지 않고 실패한다")
    void missingFileNeverBootstrapsAutomatically() {
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));

        assertCryptoFailure(() -> verification.verify(file(), "dev"));
        assertCryptoFailure(() -> verification.register(file(), "dev"));

        assertThat(file()).doesNotExist();
    }

    @Test
    @DisplayName("신규 키는 명시적 등록 전에는 거절하고 등록 후에도 이전 검증 자료를 보존한다")
    void registerOnlyAddsNewKeysAndAllowsActiveKeyRotation() throws IOException {
        Path file = file();
        verifier("old", Map.of("old", OLD_KEY)).createInitialEvidence(file, "dev");
        Map<String, Object> oldCheck = check(read(file), "old");
        DiarySourceTextKeyVerification rotated = verifier("new", Map.of("old", OLD_KEY, "new", NEW_KEY));

        assertCryptoFailure(() -> rotated.verify(file, "dev"));
        rotated.register(file, "dev");
        rotated.verify(file, "dev");

        Map<String, Object> manifest = read(file);
        assertThat(checks(manifest)).containsOnlyKeys("old", "new");
        assertThat(check(manifest, "old")).isEqualTo(oldCheck);
        verifier("old", Map.of("old", OLD_KEY, "new", NEW_KEY)).verify(file, "dev");
    }

    @Test
    @DisplayName("기존 키를 삭제하거나 변조한 상태에서는 신규 키를 등록하지 못한다")
    void rejectRemovedOrChangedExistingKeysBeforeAddingNewOnes() throws IOException {
        Path file = file();
        verifier("old", Map.of("old", OLD_KEY)).createInitialEvidence(file, "dev");
        byte[] original = Files.readAllBytes(file);

        assertCryptoFailure(() -> verifier("new", Map.of("new", NEW_KEY)).register(file, "dev"));
        assertCryptoFailure(() -> verifier("new", Map.of("old", NEW_KEY, "new", OLD_KEY))
                .register(file, "dev"));
        assertCryptoFailure(() -> verifier("new", Map.of("new", NEW_KEY)).verify(file, "dev"));

        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    @Test
    @DisplayName("등록할 신규 키가 없으면 기존 파일과 수정 시각을 유지한다")
    void registrationWithoutNewKeysIsIdempotent() throws IOException {
        Path file = file();
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));
        verification.createInitialEvidence(file, "dev");
        byte[] original = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);

        verification.register(file, "dev");

        assertThat(Files.readAllBytes(file)).isEqualTo(original);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
    }

    @Test
    @DisplayName("원자적으로 추가 등록한 파일도 기존 소유자와 그룹 및 접근 권한을 유지한다")
    void preservePermissionsAndOwnershipWhenReplacingManifest() throws IOException {
        Path file = file();
        verifier("old", Map.of("old", OLD_KEY)).createInitialEvidence(file, "dev");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"));
        PosixFileAttributes original = Files.readAttributes(file, PosixFileAttributes.class);

        verifier("new", Map.of("old", OLD_KEY, "new", NEW_KEY)).register(file, "dev");

        PosixFileAttributes replaced = Files.readAttributes(file, PosixFileAttributes.class);
        assertThat(replaced.permissions()).isEqualTo(original.permissions());
        assertThat(replaced.owner()).isEqualTo(original.owner());
        assertThat(replaced.group()).isEqualTo(original.group());
        try (var files = Files.list(directory)) {
            assertThat(files.map(path -> path.getFileName().toString()))
                    .containsExactlyInAnyOrder("keys.json", "keys.json.lock");
        }
    }

    @Test
    @DisplayName("동시 변경 잠금이 있으면 등록을 거절하지만 읽기 검증은 계속 수행한다")
    void rejectConcurrentMutationWithoutBlockingReadOnlyVerification() throws IOException {
        Path file = file();
        verifier("old", Map.of("old", OLD_KEY)).createInitialEvidence(file, "dev");
        byte[] original = Files.readAllBytes(file);
        try (FileChannel channel = FileChannel.open(lockFile(), StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            assertThat(lock.isValid()).isTrue();
            assertCryptoFailure(() -> verifier("new", Map.of("old", OLD_KEY, "new", NEW_KEY))
                    .register(file, "dev"));
            verifier("old", Map.of("old", OLD_KEY)).verify(file, "dev");
        }
        assertThat(Files.readAllBytes(file)).isEqualTo(original);
    }

    @Test
    @DisplayName("환경이 다르거나 환경 이름만 고친 검증 자료도 인증 실패로 거절한다")
    void bindEvidenceToEnvironmentInMetadataAndAuthenticatedContext() throws IOException {
        Path file = file();
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));
        verification.createInitialEvidence(file, "dev");
        assertCryptoFailure(() -> verification.verify(file, "prod"));
        Map<String, Object> manifest = read(file);
        manifest.put("environment", "prod");
        write(file, manifest);

        assertCryptoFailure(() -> verification.verify(file, "prod"));
        assertCryptoFailure(() -> verification.register(file, "prod"));
    }

    @Test
    @DisplayName("지원하지 않는 환경은 초기화와 검증 및 추가 등록에서 모두 거절한다")
    void rejectUnsupportedEnvironment() {
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));
        for (String environment : new String[] {null, "", "production", "test"}) {
            assertCryptoFailure(() -> verification.createInitialEvidence(file(), environment));
            assertCryptoFailure(() -> verification.verify(file(), environment));
            assertCryptoFailure(() -> verification.register(file(), environment));
        }
        assertThat(file()).doesNotExist();
    }

    @Test
    @DisplayName("암호문이 유효해도 알려진 검증 문구나 HMAC 지문이 다르면 거절한다")
    void verifyKnownPlaintextAndExpectedFingerprint() throws IOException {
        Path file = file();
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));
        verification.createInitialEvidence(file, "dev");
        Map<String, Object> original = read(file);
        Map<String, Object> wrongFingerprint = read(file);
        check(wrongFingerprint, "old").put("protectedFingerprint", "a".repeat(64));
        write(file, wrongFingerprint);
        assertCryptoFailure(() -> verification.verify(file, "dev"));

        String wrongText = new AesGcmDiarySourceTextCipher(properties("old", Map.of("old", OLD_KEY)))
                .encrypt(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        UUID.fromString("00000000-0000-0000-0000-000000000002"), "다른 검증 문구");
        check(original, "old").put("ciphertext", wrongText);
        write(file, original);
        assertCryptoFailure(() -> verification.verify(file, "dev"));
    }

    @Test
    @DisplayName("키 이름과 암호문의 키 식별자가 다르면 검증 자료를 거절한다")
    void rejectRelabeledCiphertext() throws IOException {
        Path file = file();
        verifier("old", Map.of("old", OLD_KEY)).createInitialEvidence(file, "dev");
        Map<String, Object> manifest = read(file);
        checks(manifest).put("new", checks(manifest).remove("old"));
        write(file, manifest);

        assertCryptoFailure(() -> verifier("new", Map.of("new", OLD_KEY)).verify(file, "dev"));
    }

    @Test
    @DisplayName("깨진 JSON과 중복 필드 및 지원하지 않는 구조는 파일 내용을 예외에 넣지 않고 거절한다")
    void rejectMalformedAndAmbiguousManifestWithoutLeakingItsContents() throws IOException {
        Path file = file();
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));
        verification.createInitialEvidence(file, "dev");
        String valid = Files.readString(file);
        String duplicate = valid.substring(0, valid.lastIndexOf('}')) + ",\"environment\":\"dev\"}";
        for (String invalid : new String[] {"private-invalid-content", "{}", valid + "{}", duplicate,
                "{\"formatVersion\":1,\"environment\":\"dev\",\"checks\":{}}"}) {
            Files.writeString(file, invalid);
            assertCryptoFailure(() -> verification.verify(file, "dev"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> unsupported = JSON.readValue(valid, Map.class);
        unsupported.put("formatVersion", 2);
        write(file, unsupported);
        assertCryptoFailure(() -> verification.verify(file, "dev"));
        unsupported.put("formatVersion", 1);
        unsupported.put("unknown", "private-unknown-content");
        write(file, unsupported);
        assertCryptoFailure(() -> verification.verify(file, "dev"));
    }

    @Test
    @DisplayName("비어 있거나 너무 큰 파일 및 심볼릭 링크는 거절한다")
    void rejectEmptyOversizedOrSymbolicLinkFiles() throws IOException {
        Path file = file();
        DiarySourceTextKeyVerification verification = verifier("old", Map.of("old", OLD_KEY));
        Files.createFile(file);
        assertCryptoFailure(() -> verification.verify(file, "dev"));
        Files.write(file, new byte[65_537]);
        assertCryptoFailure(() -> verification.verify(file, "dev"));
        Files.delete(file);
        verification.createInitialEvidence(file, "dev");
        Path link = directory.resolve("link.json");
        Files.createSymbolicLink(link, file);
        assertCryptoFailure(() -> verification.verify(link, "dev"));
    }

    private Path file() {
        return directory.resolve("keys.json");
    }

    private Path lockFile() {
        return directory.resolve("keys.json.lock");
    }

    private static DiarySourceTextEncryptionProperties properties(String active, Map<String, String> keys) {
        return new DiarySourceTextEncryptionProperties(active, keys);
    }

    private static DiarySourceTextKeyVerification verifier(String active, Map<String, String> keys) {
        return new DiarySourceTextKeyVerification(properties(active, keys));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(Path file) throws IOException {
        return JSON.readValue(Files.readAllBytes(file), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> checks(Map<String, Object> manifest) {
        return (Map<String, Object>) manifest.get("checks");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> check(Map<String, Object> manifest, String keyId) {
        return (Map<String, Object>) checks(manifest).get(keyId);
    }

    private static void write(Path file, Map<String, Object> manifest) throws IOException {
        Files.write(file, JSON.writeValueAsBytes(manifest));
    }

    private static String encodedKey(byte value) {
        byte[] key = new byte[32];
        Arrays.fill(key, value);
        return Base64.getEncoder().encodeToString(key);
    }

    private static void assertCryptoFailure(ThrowingCallable operation) {
        assertThatThrownBy(operation).isExactlyInstanceOf(DiarySourceTextCryptoException.class)
                .hasMessage("일기 원문 암호화 처리에 실패했습니다.").hasNoCause();
    }
}
