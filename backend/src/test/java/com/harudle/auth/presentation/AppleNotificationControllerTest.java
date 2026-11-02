package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.WithdrawalService;
import com.harudle.auth.infrastructure.oauth.AppleNotificationVerifier;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AppleNotificationControllerTest {

    private final AppleNotificationVerifier verifier = mock(AppleNotificationVerifier.class);
    private final WithdrawalService withdrawal = mock(WithdrawalService.class);
    private final AppleNotificationController controller = new AppleNotificationController(verifier, withdrawal);

    @Test
    void processesVerifiedRevocation() {
        Instant eventTime = Instant.now();
        when(verifier.verify("signed-payload"))
                .thenReturn(new AppleNotificationVerifier.Event("consent-revoked", "apple-user", eventTime));

        var response = controller.receive(new AppleNotificationController.NotificationRequest("signed-payload"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(withdrawal).completeAppleRevocation("apple-user", eventTime);
    }

    @Test
    void rejectsInvalidNotificationWithoutDeleting() {
        when(verifier.verify("invalid")).thenThrow(new IllegalArgumentException());

        var response = controller.receive(new AppleNotificationController.NotificationRequest("invalid"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(withdrawal);
    }

    @Test
    void doesNotDeleteForEmailForwardingChange() {
        when(verifier.verify("signed-payload"))
                .thenReturn(new AppleNotificationVerifier.Event("email-disabled", null, null));

        var response = controller.receive(new AppleNotificationController.NotificationRequest("signed-payload"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verifyNoInteractions(withdrawal);
    }
}
