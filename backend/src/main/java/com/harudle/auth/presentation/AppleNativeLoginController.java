package com.harudle.auth.presentation;

import com.harudle.auth.application.OAuthLoginService;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.oauth.AppleLoginCommandMapper;
import com.harudle.auth.infrastructure.oauth.AppleNativeTokenVerifier;
import com.harudle.auth.infrastructure.token.RefreshTokenCookieWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

@RestController
@RequestMapping("/api/v1/auth/apple/native")
@ConditionalOnProperty(name = "app.auth.apple.enabled", havingValue = "true")
public class AppleNativeLoginController {
    private static final String NONCE = AppleNativeLoginController.class.getName() + ".nonce";
    private static final Duration NONCE_TTL = Duration.ofMinutes(5);
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;
    private final AppleNativeTokenVerifier verifier;
    private final AppleLoginCommandMapper mapper;
    private final OAuthLoginService loginService;
    private final UserRepository users;
    private final RefreshTokenService tokens;
    private final RefreshTokenCookieWriter cookies;

    public AppleNativeLoginController(@Qualifier("authClock") Clock clock, AppleNativeTokenVerifier verifier,
            AppleLoginCommandMapper mapper, OAuthLoginService loginService, UserRepository users,
            RefreshTokenService tokens, RefreshTokenCookieWriter cookies) {
        this.clock = clock;
        this.verifier = verifier;
        this.mapper = mapper;
        this.loginService = loginService;
        this.users = users;
        this.tokens = tokens;
        this.cookies = cookies;
    }

    @PostMapping("/nonce")
    public ResponseEntity<NonceResponse> nonce(HttpServletRequest request) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        HttpSession session = request.getSession(true);
        synchronized (session) {
            session.setAttribute(NONCE, new PendingNonce(value, clock.instant().plus(NONCE_TTL)));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new NonceResponse(value));
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        if (!StringUtils.hasText(body.authorizationCode()) || !StringUtils.hasText(body.identityToken())
                || body.authorizationCode().length() > 4096 || body.identityToken().length() > 16384) {
            throw new IllegalArgumentException("Apple 인증 정보가 필요합니다.");
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new IllegalArgumentException("Apple 로그인 요청이 없습니다.");
        }
        PendingNonce pending;
        synchronized (session) {
            pending = (PendingNonce) session.getAttribute(NONCE);
            session.removeAttribute(NONCE);
        }
        if (pending == null || !clock.instant().isBefore(pending.expiresAt())) {
            throw new IllegalArgumentException("Apple 로그인 요청이 만료되었습니다.");
        }
        Jwt apple = verifier.verify(body.authorizationCode(), body.identityToken(), pending.value());
        Instant now = clock.instant();
        var result = loginService.login(mapper.map(apple.getClaims()), now);
        var user = users.findById(result.userId()).orElseThrow();
        cookies.write(response, tokens.issue(user, now));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler({IllegalArgumentException.class, JwtException.class})
    public ResponseEntity<Void> invalidLogin() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).build();
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Void> appleUnavailable() {
        return ResponseEntity.status(502).cacheControl(CacheControl.noStore()).build();
    }

    record NonceResponse(String nonce) {}
    record LoginRequest(String authorizationCode, String identityToken) {}
    record PendingNonce(String value, Instant expiresAt) implements java.io.Serializable {}
}
