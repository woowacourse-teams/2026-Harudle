package com.harudle.auth.application;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.profile.service.ProfileImageUrlResolver;
import java.net.URI;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CurrentUserService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final ProfileImageUrlResolver profileImageUrlResolver;

    public CurrentUserService(
            UserRepository userRepository,
            OAuthAccountRepository oauthAccountRepository,
            ProfileImageUrlResolver profileImageUrlResolver
    ) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.profileImageUrlResolver = profileImageUrlResolver;
    }

    @Transactional(readOnly = true)
    public CurrentUserResult find(UUID userId) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");

        User user = userRepository.findById(userId)
                .orElseThrow(InvalidCurrentUserException::new);

        validateActiveUser(user);

        OAuthAccount oauthAccount = oauthAccountRepository.findByUser_Id(userId)
                .orElseThrow(InvalidCurrentUserException::new);

        Short imageCode = user.getProfileImageCode();
        URI profileImageUrl = null;
        if (imageCode != null) {
            profileImageUrl = profileImageUrlResolver.resolve(imageCode);
        }

        return CurrentUserResult.from(user, oauthAccount, profileImageUrl);
    }

    private void validateActiveUser(User user) {
        if (!user.isDeleted()) {
            return;
        }

        throw new InvalidCurrentUserException();
    }

}
