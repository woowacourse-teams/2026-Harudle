package com.harudle.guest.presentation;

import com.harudle.guest.application.GuestSessionService;
import com.harudle.guest.application.IssuedGuestSession;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieReader;
import com.harudle.guest.infrastructure.cookie.GuestSessionCookieWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/guest")
public class GuestSessionController {

    private final GuestSessionService guestSessionService;
    private final GuestSessionCookieReader cookieReader;
    private final GuestSessionCookieWriter cookieWriter;
    private final Clock clock;

    public GuestSessionController(
            GuestSessionService guestSessionService,
            GuestSessionCookieReader cookieReader,
            GuestSessionCookieWriter cookieWriter,
            @Qualifier("authClock")
            Clock authClock
    ) {
        this.guestSessionService = guestSessionService;
        this.cookieReader = cookieReader;
        this.cookieWriter = cookieWriter;
        this.clock = Objects.requireNonNull(authClock, "authClock는 필수입니다.");
    }

    @PostMapping("/session")
    public ResponseEntity<Void> issue(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        String currentRawToken = cookieReader.read(request).orElse(null);

        IssuedGuestSession issuedSession = guestSessionService.issueOrReuse(
                currentRawToken,
                now
        );

        cookieWriter.write(response, issuedSession, now);

        return ResponseEntity.noContent()
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
