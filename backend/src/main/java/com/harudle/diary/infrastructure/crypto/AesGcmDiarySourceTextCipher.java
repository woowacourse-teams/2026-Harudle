package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Version 1 stores {@code keyId:Base64(nonce[12] || ciphertext || authenticationTag[16])}.
 * The version column, key identifier, diary identifier and owner identifier are authenticated with the ciphertext.
 */
public final class AesGcmDiarySourceTextCipher implements DiarySourceTextCipher {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final String MAC_ALGORITHM = "HmacSHA256";
    private static final byte[] FINGERPRINT_KEY_CONTEXT = "harudle.diary.request-fingerprint.key:v1"
            .getBytes(StandardCharsets.UTF_8);
    private static final Pattern FINGERPRINT_PATTERN = Pattern.compile("[0-9a-f]{64}");
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final int MAX_PLAINTEXT_BYTES = Diary.MAX_SOURCE_TEXT_CODE_POINTS * 4;
    private static final int MAX_PAYLOAD_BYTES = NONCE_BYTES + MAX_PLAINTEXT_BYTES + TAG_BYTES;
    private static final int MAX_ENVELOPE_CHARACTERS = 64 + 1 + ((MAX_PAYLOAD_BYTES + 2) / 3) * 4;

    private final String activeKeyId;
    private final Map<String, SecretKey> keys;
    private final SecureRandom secureRandom = new SecureRandom();

    public AesGcmDiarySourceTextCipher(DiarySourceTextEncryptionProperties properties) {
        if (properties == null) {
            throw new DiarySourceTextCryptoException();
        }
        activeKeyId = properties.activeKeyId();
        Map<String, SecretKey> decodedKeys = new HashMap<>();
        properties.keys().forEach((keyId, encodedKey) -> {
            byte[] decodedKey = Base64.getDecoder().decode(encodedKey);
            try {
                decodedKeys.put(keyId, new SecretKeySpec(decodedKey, "AES"));
            } finally {
                Arrays.fill(decodedKey, (byte) 0);
            }
        });
        keys = Map.copyOf(decodedKeys);
    }

    @Override
    public String encrypt(UUID diaryId, UUID userId, String plaintext) {
        validateIdentifiers(diaryId, userId);
        byte[] plaintextBytes = null;
        try {
            plaintextBytes = encodeStrictly(Diary.normalizeSourceText(plaintext));
            byte[] nonce = new byte[NONCE_BYTES];
            secureRandom.nextBytes(nonce);
            Cipher cipher = newCipher(Cipher.ENCRYPT_MODE, activeKeyId, nonce, diaryId, userId);
            byte[] ciphertext = cipher.doFinal(plaintextBytes);
            byte[] payload = ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce)
                    .put(ciphertext)
                    .array();
            return activeKeyId + ":" + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException | CharacterCodingException | IllegalArgumentException exception) {
            throw new DiarySourceTextCryptoException();
        } finally {
            if (plaintextBytes != null) {
                Arrays.fill(plaintextBytes, (byte) 0);
            }
        }
    }

    @Override
    public String decrypt(UUID diaryId, UUID userId, String storedText, int version) {
        validateIdentifiers(diaryId, userId);
        byte[] plaintextBytes = null;
        try {
            if (version == LEGACY_VERSION) {
                String normalized = Diary.normalizeSourceText(storedText);
                plaintextBytes = encodeStrictly(normalized);
                return normalized;
            }
            if (version != CURRENT_VERSION) {
                throw new DiarySourceTextCryptoException();
            }
            EncryptedEnvelope envelope = parseEncryptedEnvelope(storedText);
            byte[] payload = envelope.payload();
            byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
            Cipher cipher = newCipher(Cipher.DECRYPT_MODE, envelope.keyId(), nonce, diaryId, userId);
            plaintextBytes = cipher.doFinal(payload, NONCE_BYTES, payload.length - NONCE_BYTES);
            return Diary.normalizeSourceText(decodeStrictly(plaintextBytes));
        } catch (GeneralSecurityException | CharacterCodingException | IllegalArgumentException exception) {
            throw new DiarySourceTextCryptoException();
        } finally {
            if (plaintextBytes != null) {
                Arrays.fill(plaintextBytes, (byte) 0);
            }
        }
    }

    @Override
    public String protectRequestFingerprint(
            UUID diaryId,
            UUID userId,
            String storedText,
            int version,
            String fingerprint
    ) {
        validateIdentifiers(diaryId, userId);
        if (fingerprint == null || !FINGERPRINT_PATTERN.matcher(fingerprint).matches()) {
            throw new DiarySourceTextCryptoException();
        }
        if (version == LEGACY_VERSION) {
            return fingerprint;
        }
        if (version != CURRENT_VERSION) {
            throw new DiarySourceTextCryptoException();
        }
        byte[] sourceKeyBytes = null;
        byte[] fingerprintKeyBytes = null;
        try {
            EncryptedEnvelope envelope = parseEncryptedEnvelope(storedText);
            sourceKeyBytes = keys.get(envelope.keyId()).getEncoded();
            Mac derivation = Mac.getInstance(MAC_ALGORITHM);
            derivation.init(new SecretKeySpec(sourceKeyBytes, MAC_ALGORITHM));
            fingerprintKeyBytes = derivation.doFinal(FINGERPRINT_KEY_CONTEXT);

            Mac fingerprintMac = Mac.getInstance(MAC_ALGORITHM);
            fingerprintMac.init(new SecretKeySpec(fingerprintKeyBytes, MAC_ALGORITHM));
            fingerprintMac.update(("harudle.diary.request-fingerprint:v1:" + envelope.keyId()
                    + ":" + diaryId + ":" + userId + ":").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(fingerprintMac.doFinal(fingerprint.getBytes(StandardCharsets.US_ASCII)));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new DiarySourceTextCryptoException();
        } finally {
            if (sourceKeyBytes != null) {
                Arrays.fill(sourceKeyBytes, (byte) 0);
            }
            if (fingerprintKeyBytes != null) {
                Arrays.fill(fingerprintKeyBytes, (byte) 0);
            }
        }
    }

    private EncryptedEnvelope parseEncryptedEnvelope(String storedText) {
        if (storedText == null || storedText.length() > MAX_ENVELOPE_CHARACTERS) {
            throw new DiarySourceTextCryptoException();
        }
        int separator = storedText.indexOf(':');
        if (separator <= 0) {
            throw new DiarySourceTextCryptoException();
        }
        String keyId = storedText.substring(0, separator);
        if (!DiarySourceTextEncryptionProperties.isValidKeyId(keyId) || !keys.containsKey(keyId)) {
            throw new DiarySourceTextCryptoException();
        }
        String encodedPayload = storedText.substring(separator + 1);
        byte[] payload = Base64.getDecoder().decode(encodedPayload);
        if (payload.length < NONCE_BYTES + TAG_BYTES + 1 || payload.length > MAX_PAYLOAD_BYTES
                || !Base64.getEncoder().encodeToString(payload).equals(encodedPayload)) {
            throw new DiarySourceTextCryptoException();
        }
        return new EncryptedEnvelope(keyId, payload);
    }

    private Cipher newCipher(int mode, String keyId, byte[] nonce, UUID diaryId, UUID userId)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(mode, keys.get(keyId), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(("harudle.diary.source-text:v1:" + keyId + ":" + diaryId + ":" + userId)
                .getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static byte[] encodeStrictly(String plaintext) throws CharacterCodingException {
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .encode(CharBuffer.wrap(plaintext));
        byte[] result = new byte[encoded.remaining()];
        encoded.get(result);
        Arrays.fill(encoded.array(), (byte) 0);
        return result;
    }

    private static String decodeStrictly(byte[] plaintext) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext))
                .toString();
    }

    private static void validateIdentifiers(UUID diaryId, UUID userId) {
        if (diaryId == null || userId == null) {
            throw new DiarySourceTextCryptoException();
        }
    }

    private record EncryptedEnvelope(String keyId, byte[] payload) {
    }
}
