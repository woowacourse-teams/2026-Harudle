package com.harudle.auth.presentation;

import com.harudle.auth.application.WithdrawalService;
import com.harudle.auth.infrastructure.oauth.AppleNotificationVerifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/apple/notifications")
public class AppleNotificationController {

    private final AppleNotificationVerifier verifier;
    private final WithdrawalService withdrawal;

    public AppleNotificationController(AppleNotificationVerifier verifier, WithdrawalService withdrawal) {
        this.verifier = verifier;
        this.withdrawal = withdrawal;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@Valid @RequestBody NotificationRequest request) {
        AppleNotificationVerifier.Event event;
        try {
            event = verifier.verify(request.payload());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
        }
        if (event.requiresWithdrawal()) {
            withdrawal.completeAppleRevocation(Objects.requireNonNull(event.subject()), Objects.requireNonNull(event.occurredAt()));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }

    public record NotificationRequest(@NotBlank @Size(max = 16384) String payload) {}
}
