package com.harudle.auth.infrastructure.oauth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class AppleTokenStoreTest {

    @Test
    void keepsLoginAvailableWhenEncryptionOrStorageFails() {
        var jdbc = mock(JdbcTemplate.class);
        var missingKey = new AppleTokenStore(jdbc, "");
        assertThatCode(() -> missingKey.save(UUID.randomUUID(), "app", "id", "access", "refresh", null))
                .doesNotThrowAnyException();

        var store = new AppleTokenStore(jdbc, Base64.getEncoder().encodeToString(new byte[32]));
        when(jdbc.update(anyString(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatCode(() -> store.save(UUID.randomUUID(), "app", "id", "access", "refresh", null))
                .doesNotThrowAnyException();
        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
