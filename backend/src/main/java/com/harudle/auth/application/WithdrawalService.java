package com.harudle.auth.application;

import com.harudle.auth.application.WithdrawalDatabaseService.WithdrawalGenerationInProgressException;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.WithdrawalImageRepository.ImageTargets;
import com.harudle.auth.infrastructure.oauth.SocialUnlinkCredentials;
import com.harudle.auth.infrastructure.oauth.SocialUnlinkResolver;
import com.harudle.auth.infrastructure.oauth.SocialUnlinkResult;
import com.harudle.generation.diary.service.DiaryGenerationStatusQueryService;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(propagation = Propagation.NEVER)
public class WithdrawalService {

    private static final Logger LOGGER = LoggerFactory.getLogger(WithdrawalService.class);

    private final OAuthAccountRepository accounts;
    private final DiaryGenerationStatusQueryService generations;
    private final SocialUnlinkResolver unlinkResolver;
    private final WithdrawalDatabaseService database;
    private final WithdrawalImageCleanupService images;
    private final OAuth2AuthorizedClientService authorizedClients;

    public WithdrawalService(OAuthAccountRepository accounts, DiaryGenerationStatusQueryService generations,
            SocialUnlinkResolver unlinkResolver, WithdrawalDatabaseService database, WithdrawalImageCleanupService images,
            OAuth2AuthorizedClientService authorizedClients) {
        this.accounts = accounts;
        this.generations = generations;
        this.unlinkResolver = unlinkResolver;
        this.database = database;
        this.images = images;
        this.authorizedClients = authorizedClients;
    }

    public SocialUnlinkResult begin(UUID userId) {
        var account = accounts.findByUser_Id(userId).orElseThrow(InvalidCurrentUserException::new);
        requireNoUnfinishedGeneration(userId);
        var result = unlinkResolver.begin(account);
        return switch (result) {
            case GOOGLE_AUTHENTICATION_REQUIRED -> result;
            case REVOKED, APPLE_TOKEN_UNAVAILABLE -> {
                finish(userId, account);
                yield result;
            }
        };
    }

    /** userId는 인증된 탈퇴 요청과 연결된 값이어야 한다. */
    public void completeGoogle(UUID userId, OAuth2AuthenticationToken authentication, OAuth2AuthorizedClient client) {
        var account = accounts.findByUser_Id(userId).orElseThrow(InvalidCurrentUserException::new);
        if (account.getProvider() != OAuthProvider.GOOGLE) {
            throw new IllegalArgumentException("구글 회원만 구글 인증으로 탈퇴할 수 있습니다.");
        }
        requireNoUnfinishedGeneration(userId);
        var result = unlinkResolver.unlink(account, new SocialUnlinkCredentials.Google(authentication, client));
        if (result != SocialUnlinkResult.REVOKED) {
            throw new IllegalStateException("구글 연결 해제를 확인할 수 없습니다.");
        }
        finish(userId, account);
    }

    public void completeAppleRevocation(String subject, Instant eventTime) {
        var account = accounts.findByProviderAndProviderSubject(OAuthProvider.APPLE, subject);
        if (account.isEmpty()) {
            return;
        }
        var targets = database.deleteAfterAppleRevocation(account.get().getUser().getId(), subject, eventTime);
        if (targets.isEmpty()) {
            LOGGER.info("event=apple_revocation_skipped reason=account_newer_or_missing");
            return;
        }
        cleanUp(account.get(), targets.get());
    }

    private void requireNoUnfinishedGeneration(UUID userId) {
        if (generations.hasUnfinishedGeneration(userId)) {
            throw new WithdrawalGenerationInProgressException();
        }
    }

    private void finish(UUID userId, OAuthAccount account) {
        var targets = database.delete(userId);
        cleanUp(account, targets);
    }

    private void cleanUp(OAuthAccount account, ImageTargets targets) {
        try {
            authorizedClients.removeAuthorizedClient(account.getProvider().name().toLowerCase(Locale.ROOT),
                    account.getProviderSubject());
        } catch (RuntimeException exception) {
            LOGGER.warn("event=withdrawal_authorized_client_remove_failed exceptionType={}",
                    exception.getClass().getSimpleName());
        }
        images.delete(targets);
    }
}
