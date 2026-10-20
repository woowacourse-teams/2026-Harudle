package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.domain.DiarySourceTextCipher;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks persisted key identities using public canaries with reserved UUIDs and a known request hash.
 * Missing or mismatching evidence fails closed; ordinary verification never creates or replaces evidence.
 */
public final class DiarySourceTextKeyVerification {

    private static final int MAX_FILE_BYTES = 65_536;
    private static final Set<String> ROOT_FIELDS = Set.of("formatVersion", "environment", "checks");
    private static final Set<String> CHECK_FIELDS = Set.of("ciphertext", "protectedFingerprint");
    private static final String KNOWN_REQUEST_FINGERPRINT =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final DiarySourceTextEncryptionProperties properties;
    private final DiarySourceTextCipher cipher;

    public DiarySourceTextKeyVerification(DiarySourceTextEncryptionProperties properties) {
        this.properties = properties;
        this.cipher = new AesGcmDiarySourceTextCipher(properties);
    }

    /** Verifies all configured keys against existing evidence without encrypting or writing any file. */
    public void verify(Path file, String environment) {
        safely(() -> {
            Manifest manifest = read(file, environment);
            require(manifest.checks().keySet().equals(properties.keys().keySet()));
            verifyRegisteredChecks(manifest);
        });
    }

    /**
     * Explicitly establishes initial trust with CREATE_NEW. The caller must first establish that these keys
     * are trusted, including checking existing v1 data when present; this operation cannot prove that history.
     */
    public void initialize(Path file, String environment) {
        safely(() -> withMutationLock(file, () -> {
            validateEnvironment(environment);
            require(!Files.exists(file, LinkOption.NOFOLLOW_LINKS));
            Manifest manifest = new Manifest(1, environment, createChecks(environment));
            writeNew(file, encode(manifest));
        }));
    }

    /** Adds only new key IDs after verifying every existing key; existing identities cannot change or disappear. */
    public void register(Path file, String environment) {
        safely(() -> withMutationLock(file, () -> {
            Manifest manifest = read(file, environment);
            verifyRegisteredChecks(manifest);
            registerMissingKeys(file, manifest);
        }));
    }

    private Manifest read(Path file, String environment) throws IOException {
        validateEnvironment(environment);
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS));
        require(Files.size(file) > 0 && Files.size(file) <= MAX_FILE_BYTES);
        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_FILE_BYTES + 1);
        }
        require(bytes.length > 0 && bytes.length <= MAX_FILE_BYTES);
        JsonNode root = JSON.readTree(bytes);
        requireFields(root, ROOT_FIELDS);
        require(root.get("formatVersion").isInt() && root.get("formatVersion").asInt() == 1);
        require(root.get("environment").isString() && environment.equals(root.get("environment").asString()));
        Map<String, KeyCheck> checks = readChecks(root.get("checks"));
        require(!checks.isEmpty());
        return new Manifest(1, environment, checks);
    }

    private Map<String, KeyCheck> readChecks(JsonNode node) {
        require(node != null && node.isObject());
        Map<String, KeyCheck> checks = new LinkedHashMap<>();
        node.properties().forEach(entry -> checks.put(entry.getKey(), readCheck(entry.getKey(), entry.getValue())));
        return checks;
    }

    private KeyCheck readCheck(String keyId, JsonNode node) {
        require(DiarySourceTextEncryptionProperties.isValidKeyId(keyId));
        requireFields(node, CHECK_FIELDS);
        require(node.get("ciphertext").isString() && node.get("protectedFingerprint").isString());
        String ciphertext = node.get("ciphertext").asString();
        String fingerprint = node.get("protectedFingerprint").asString();
        require(ciphertext.startsWith(keyId + ":") && fingerprint.matches("[0-9a-f]{64}"));
        return new KeyCheck(ciphertext, fingerprint);
    }

    private void verifyRegisteredChecks(Manifest manifest) {
        require(properties.keys().keySet().containsAll(manifest.checks().keySet()));
        manifest.checks().forEach((keyId, check) -> verifyCheck(manifest.environment(), keyId, check));
    }

    private void verifyCheck(String environment, String keyId, KeyCheck check) {
        UUID diaryId = diaryId(environment);
        UUID userId = userId(environment);
        require(knownText(environment, keyId).equals(
                cipher.decrypt(diaryId, userId, check.ciphertext(), DiarySourceTextCipher.CURRENT_VERSION)
        ));
        String actualFingerprint = cipher.protectRequestFingerprint(
                diaryId, userId, check.ciphertext(), DiarySourceTextCipher.CURRENT_VERSION, KNOWN_REQUEST_FINGERPRINT
        );
        require(MessageDigest.isEqual(check.protectedFingerprint().getBytes(StandardCharsets.US_ASCII),
                actualFingerprint.getBytes(StandardCharsets.US_ASCII)));
    }

    private Map<String, KeyCheck> createChecks(String environment) {
        Map<String, KeyCheck> checks = new LinkedHashMap<>();
        properties.keys().keySet().stream().sorted()
                .forEach(keyId -> checks.put(keyId, createCheck(environment, keyId)));
        return checks;
    }

    private KeyCheck createCheck(String environment, String keyId) {
        DiarySourceTextCipher keyCipher = new AesGcmDiarySourceTextCipher(
                new DiarySourceTextEncryptionProperties(keyId, properties.keys())
        );
        String ciphertext = keyCipher.encrypt(diaryId(environment), userId(environment), knownText(environment, keyId));
        String fingerprint = keyCipher.protectRequestFingerprint(diaryId(environment), userId(environment),
                ciphertext, DiarySourceTextCipher.CURRENT_VERSION, KNOWN_REQUEST_FINGERPRINT);
        return new KeyCheck(ciphertext, fingerprint);
    }

    private void registerMissingKeys(Path file, Manifest manifest) throws IOException {
        if (manifest.checks().keySet().equals(properties.keys().keySet())) {
            return;
        }
        Map<String, KeyCheck> checks = new LinkedHashMap<>(manifest.checks());
        properties.keys().keySet().stream().sorted().filter(keyId -> !checks.containsKey(keyId))
                .forEach(keyId -> checks.put(keyId, createCheck(manifest.environment(), keyId)));
        replaceAtomically(file, encode(new Manifest(1, manifest.environment(), checks)));
    }

    private byte[] encode(Manifest manifest) {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
        require(bytes.length <= MAX_FILE_BYTES);
        return bytes;
    }

    private void writeNew(Path file, byte[] bytes) throws IOException {
        try (FileChannel output = FileChannel.open(file,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            writeFully(output, bytes);
        }
    }

    private void replaceAtomically(Path file, byte[] bytes) throws IOException {
        PosixFileAttributes attributes = Files.readAttributes(
                file, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS
        );
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), ".diary-key-check-", ".json");
        try {
            writeReplacement(temporary, bytes, attributes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void writeReplacement(Path file, byte[] bytes, PosixFileAttributes attributes) throws IOException {
        try (FileChannel output = FileChannel.open(file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            writeFully(output, bytes);
        }
        PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        view.setOwner(attributes.owner());
        view.setGroup(attributes.group());
        view.setPermissions(attributes.permissions());
    }

    private void writeFully(FileChannel output, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            output.write(buffer);
        }
        output.force(true);
    }

    private void withMutationLock(Path file, CheckedAction action) throws IOException {
        Path lockFile = file.toAbsolutePath().resolveSibling(file.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lockFile,
                Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                FileLock lock = channel.tryLock()) {
            require(lock != null);
            action.run();
        }
    }

    private void requireFields(JsonNode node, Set<String> fields) {
        require(node != null && node.isObject() && node.size() == fields.size());
        require(fields.stream().allMatch(node::has));
    }

    private void validateEnvironment(String environment) {
        require("dev".equals(environment) || "prod".equals(environment));
    }

    private UUID diaryId(String environment) {
        return UUID.fromString("dev".equals(environment)
                ? "00000000-0000-0000-0000-000000000001" : "00000000-0000-0000-0000-000000000003");
    }

    private UUID userId(String environment) {
        return UUID.fromString("dev".equals(environment)
                ? "00000000-0000-0000-0000-000000000002" : "00000000-0000-0000-0000-000000000004");
    }

    private String knownText(String environment, String keyId) {
        return "harudle diary source text key verification v1 " + environment + " " + keyId;
    }

    private void safely(CheckedAction action) {
        try {
            action.run();
        } catch (IOException | RuntimeException exception) {
            throw new DiarySourceTextCryptoException();
        }
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new DiarySourceTextCryptoException();
        }
    }

    @FunctionalInterface
    private interface CheckedAction {

        void run() throws IOException;
    }

    private record Manifest(int formatVersion, String environment, Map<String, KeyCheck> checks) {
    }

    private record KeyCheck(String ciphertext, String protectedFingerprint) {
    }
}
