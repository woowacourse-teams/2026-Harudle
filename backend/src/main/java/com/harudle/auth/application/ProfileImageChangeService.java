package com.harudle.auth.application;

import com.harudle.auth.domain.User;
import com.harudle.auth.domain.exception.PublicProfileNotAssignedException;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileImageChangeService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final Clock clock;

    public ProfileImageChangeService(
            UserRepository userRepository,
            OAuthAccountRepository oauthAccountRepository,
            @Qualifier("authClock") Clock clock
    ) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.clock = clock;
    }

    @Transactional
    public void changeDefault(UUID userId, int imageCode) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(InvalidCurrentUserException::new);

        if (user.isDeleted() || oauthAccountRepository.findByUser_Id(userId).isEmpty()) {
            throw new InvalidCurrentUserException();
        }

        if (user.getNickname() == null || user.getProfileImageCode() == null) {
            throw new PublicProfileNotAssignedException();
        }

        user.changeDefaultProfileImage(imageCode, clock.instant());
    }
}
