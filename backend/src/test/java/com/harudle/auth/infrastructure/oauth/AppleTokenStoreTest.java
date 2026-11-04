package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

@ExtendWith(OutputCaptureExtension.class)
class AppleTokenStoreTest {

    @Test
    void keepsLoginAvailableWhenEncryptionOrStorageFails(CapturedOutput output) {
        var repository = mock(AppleTokenRepository.class);
        var missingKey = new AppleTokenStore(repository, "private-invalid-key");
        assertThat(output).contains("event=apple_token_key_invalid reason=INVALID_KEY")
                .doesNotContain("private-invalid-key");
        assertThatCode(() -> missingKey.save(UUID.randomUUID(), "app", "id", "access", "refresh", null))
                .doesNotThrowAnyException();
        assertThat(output).contains("event=apple_token_save_failed reason=INVALID_KEY");
        verifyNoInteractions(repository);

        var store = new AppleTokenStore(repository, Base64.getEncoder().encodeToString(new byte[32]));
        store.save(UUID.randomUUID(), "app", "", "access", "refresh", null);
        assertThat(output).contains("event=apple_token_save_failed reason=MISSING_TOKEN");
        verifyNoInteractions(repository);
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(repository).save(any(), any(), any(), any(), any(), any());
        assertThatCode(() -> store.save(UUID.randomUUID(), "app", "secret-id-token", "secret-access-token",
                "secret-refresh-token", null))
                .doesNotThrowAnyException();
        assertThat(output).contains("event=apple_token_save_failed reason=DATABASE_ERROR")
                .doesNotContain("secret-id-token", "secret-access-token", "secret-refresh-token", "unavailable");
        verify(repository).save(any(), any(), any(), any(), any(), any());
    }
}
