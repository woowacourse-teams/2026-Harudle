package com.harudle.diary.infrastructure.crypto;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("harudle.diary.source-text-encryption")
public record DiarySourceTextEncryptionProperties(String activeKeyId, Map<String, String> keys) {

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int KEY_BYTES = 32;

    public DiarySourceTextEncryptionProperties {
        validate(activeKeyId, keys);
        keys = Map.copyOf(keys);
    }

    static boolean isValidKeyId(String keyId) {
        return keyId != null && KEY_ID_PATTERN.matcher(keyId).matches();
    }

    private static void validate(String activeKeyId, Map<String, String> keys) {
        if (!isValidKeyId(activeKeyId) || keys == null || keys.isEmpty() || !keys.containsKey(activeKeyId)) {
            throw new DiarySourceTextCryptoException();
        }
        List<byte[]> decodedKeys = new ArrayList<>();
        try {
            keys.entrySet().forEach(entry -> addValidatedKey(entry, decodedKeys));
        } catch (IllegalArgumentException exception) {
            throw new DiarySourceTextCryptoException();
        } finally {
            decodedKeys.forEach(key -> Arrays.fill(key, (byte) 0));
        }
    }

    private static void addValidatedKey(Map.Entry<String, String> entry, List<byte[]> decodedKeys) {
        if (!isValidKeyId(entry.getKey()) || entry.getValue() == null) {
            throw new DiarySourceTextCryptoException();
        }
        byte[] decodedKey = Base64.getDecoder().decode(entry.getValue());
        decodedKeys.add(decodedKey);
        if (decodedKey.length != KEY_BYTES || containsDuplicateKey(decodedKeys, decodedKey)) {
            throw new DiarySourceTextCryptoException();
        }
    }

    private static boolean containsDuplicateKey(List<byte[]> decodedKeys, byte[] candidate) {
        return decodedKeys.stream()
                .limit(decodedKeys.size() - 1)
                .anyMatch(previous -> Arrays.equals(previous, candidate));
    }

    @Override
    public @NonNull String toString() {
        return "DiarySourceTextEncryptionProperties[activeKeyId=***, keys=***]";
    }
}
