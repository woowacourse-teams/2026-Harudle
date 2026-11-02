package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class AppleTokenStoreTest {

    @Test
    void keepsLoginAvailableWhenEncryptionOrStorageFails() {
        var repository = mock(AppleTokenRepository.class);
        var missingKey = new AppleTokenStore(repository, "");
        assertThatCode(() -> missingKey.save(UUID.randomUUID(), "app", "id", "access", "refresh", null))
                .doesNotThrowAnyException();

        var store = new AppleTokenStore(repository, Base64.getEncoder().encodeToString(new byte[32]));
        doThrow(new DataAccessResourceFailureException("unavailable"))
                .when(repository).save(any(), any(), any(), any(), any(), any());
        assertThatCode(() -> store.save(UUID.randomUUID(), "app", "id", "access", "refresh", null))
                .doesNotThrowAnyException();
        verify(repository).save(any(), any(), any(), any(), any(), any());
    }
}
