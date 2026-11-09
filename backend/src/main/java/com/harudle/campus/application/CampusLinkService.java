package com.harudle.campus.application;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.AccountSuspendedException;
import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.application.InvalidCurrentUserException;
import com.harudle.auth.domain.exception.PublicProfileNotAssignedException;
import com.harudle.auth.infrastructure.token.RefreshTokenGenerator;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.campus.infrastructure.CampusLinkCodeRepository;
import com.harudle.campus.infrastructure.CampusLinkCodeRepository.ConsumedCampusLinkCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 우테코 캠퍼스 연결.
 *
 * <p>로그인한 회원에게 60초짜리 일회용 코드를 발급하고, 캠퍼스 서버가 비밀값과 함께 그 코드를 교환하면
 * 회원 ID · 닉네임 · 프로필 사진만 알려 준다. 캠퍼스에는 하루들 토큰을 주지 않는다.
 */
@Service
public class CampusLinkService {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{43,128}");

    private final CampusLinkProperties properties;
    private final CampusLinkCodeRepository repository;
    private final CurrentUserService currentUserService;
    private final AccountAccessPolicy accountAccess;
    private final RefreshTokenGenerator tokenGenerator;
    private final RefreshTokenHasher hasher;

    public CampusLinkService(
            CampusLinkProperties properties,
            CampusLinkCodeRepository repository,
            CurrentUserService currentUserService,
            AccountAccessPolicy accountAccess,
            RefreshTokenGenerator tokenGenerator,
            RefreshTokenHasher hasher
    ) {
        this.properties = properties;
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.accountAccess = accountAccess;
        this.tokenGenerator = tokenGenerator;
        this.hasher = hasher;
    }

    @Transactional
    public IssuedCampusLinkCode issue(UUID userId, String state, Instant now) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");
        requireEnabled();
        accountAccess.requireAllowedForRequest(userId);
        CurrentUserResult user = currentUserService.find(userId);
        if (user.nickname() == null) {
            throw new PublicProfileNotAssignedException();
        }
        String code = tokenGenerator.generate();
        Instant expiresAt = now.plus(properties.codeTtl());
        repository.save(userId, hasher.hash(code), hasher.hash(requireToken(state)), expiresAt, now);
        return new IssuedCampusLinkCode(code, properties.redirectUri(), expiresAt);
    }

    @Transactional
    public CampusLinkProfile exchange(String clientSecret, String code, String state, Instant now) {
        Objects.requireNonNull(now, "now는 필수입니다.");
        requireEnabled();
        requireClient(clientSecret);
        if (!isToken(code) || !isToken(state)) {
            throw new InvalidCampusLinkCodeException();
        }
        ConsumedCampusLinkCode consumed = repository.consume(hasher.hash(code), now)
                .orElseThrow(InvalidCampusLinkCodeException::new);
        if (!sameText(consumed.stateHash(), hasher.hash(state))) {
            throw new InvalidCampusLinkCodeException();
        }
        return findProfile(consumed.userId());
    }

    // 발급 뒤 교환 전에 정지·탈퇴했거나 닉네임이 없어졌으면 교환하지 않는다.
    private CampusLinkProfile findProfile(UUID userId) {
        try {
            accountAccess.requireAllowedForRequest(userId);
            CurrentUserResult user = currentUserService.find(userId);
            if (user.nickname() == null) {
                throw new InvalidCampusLinkCodeException();
            }
            return new CampusLinkProfile(
                    user.id(),
                    user.nickname(),
                    user.profileImageUrl(),
                    user.profileImageUrlExpiresAt()
            );
        } catch (AccountSuspendedException | InvalidCurrentUserException exception) {
            throw new InvalidCampusLinkCodeException();
        }
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new CampusLinkDisabledException();
        }
    }

    private void requireClient(String clientSecret) {
        if (clientSecret == null || clientSecret.isBlank()
                || !sameText(hasher.hash(clientSecret), properties.clientSecretSha256())) {
            throw new InvalidCampusClientException();
        }
    }

    private String requireToken(String value) {
        if (!isToken(value)) {
            throw new IllegalArgumentException("state 형식이 올바르지 않습니다.");
        }
        return value;
    }

    private static boolean isToken(String value) {
        return value != null && TOKEN.matcher(value).matches();
    }

    private static boolean sameText(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8)
        );
    }
}
