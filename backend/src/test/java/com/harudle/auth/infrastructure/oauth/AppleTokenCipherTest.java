package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class AppleTokenCipherTest {

    private final AppleTokenCipher cipher = new AppleTokenCipher(Base64.getEncoder().encodeToString(new byte[32]));

    @Test
    void encryptsAndDecryptsWithDifferentValuesEachTime() {
        String first = cipher.encrypt("apple-token");
        String second = cipher.encrypt("apple-token");

        assertThat(first).isNotEqualTo("apple-token").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("apple-token");
        assertThat(cipher.decrypt(second)).isEqualTo("apple-token");
    }

    @Test
    void rejectsTamperedCiphertextAndInvalidKey() {
        byte[] encrypted = Base64.getDecoder().decode(cipher.encrypt("apple-token"));
        encrypted[encrypted.length - 1] ^= 1;

        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(encrypted)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AppleTokenCipher("invalid-key"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
