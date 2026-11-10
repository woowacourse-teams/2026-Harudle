package com.harudle.campus.application;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.CurrentUserResult;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.domain.exception.PublicProfileNotAssignedException;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 우테코 캠퍼스 연결.
 *
 * <p>로그인한 회원에게 회원 ID · 닉네임 · 프로필 사진을 담은 60초짜리 서명 토큰을 발급한다.
 * 하루들은 토큰을 저장하지 않고, 한 번만 쓰는 것은 캠퍼스가 자기 DB로 지킨다.
 */
@Service
@ConditionalOnProperty(prefix = "harudle.campus", name = "enabled", havingValue = "true")
public class CampusLinkService {

    private final CampusLinkProperties properties;
    private final CurrentUserService currentUserService;
    private final AccountAccessPolicy accountAccess;
    private final CampusLinkTokenSigner signer;

    public CampusLinkService(
            CampusLinkProperties properties,
            CurrentUserService currentUserService,
            AccountAccessPolicy accountAccess,
            CampusLinkTokenSigner signer
    ) {
        this.properties = properties;
        this.currentUserService = currentUserService;
        this.accountAccess = accountAccess;
        this.signer = signer;
    }

    public IssuedCampusLinkToken issue(UUID userId, String state, Instant now) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        Objects.requireNonNull(state, "state는 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");
        accountAccess.requireAllowedForRequest(userId);
        CurrentUserResult user = currentUserService.find(userId);
        if (user.nickname() == null) {
            throw new PublicProfileNotAssignedException();
        }
        Instant expiresAt = now.plus(properties.tokenTtl());
        String token = signer.sign(new CampusLinkClaims(
                user.id(),
                user.nickname(),
                stableProfileImage(user),
                state,
                now,
                expiresAt
        ));
        return new IssuedCampusLinkToken(token, properties.redirectUri(), expiresAt);
    }

    // 직접 올린 사진은 곧 만료되는 서명 URL이라 넘기지 않는다. 기본 이미지 주소만 넘긴다.
    private URI stableProfileImage(CurrentUserResult user) {
        if (user.profileImageUrlExpiresAt() != null) {
            return null;
        }
        return user.profileImageUrl();
    }
}
