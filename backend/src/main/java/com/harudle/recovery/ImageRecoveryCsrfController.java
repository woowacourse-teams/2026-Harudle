package com.harudle.recovery;

import com.harudle.common.security.LegacyCsrfCookieCleaner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("image-recovery")
class ImageRecoveryCsrfController {
    private final CookieCsrfTokenRepository tokens;
    private final LegacyCsrfCookieCleaner legacy;

    ImageRecoveryCsrfController(CookieCsrfTokenRepository tokens, LegacyCsrfCookieCleaner legacy) {
        this.tokens = tokens;
        this.legacy = legacy;
    }

    @GetMapping("/api/v1/auth/csrf")
    ResponseEntity<Token> issue(HttpServletRequest request, HttpServletResponse response) {
        var token = tokens.generateToken(request);
        legacy.clear(response);
        tokens.saveToken(token, request, response);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Token(token.getToken()));
    }

    record Token(String token) {
    }
}
