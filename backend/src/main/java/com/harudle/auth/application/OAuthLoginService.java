package com.harudle.auth.application;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.nickname.NicknameCandidateRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthLoginService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final NicknameCandidateRepository nicknameCandidateRepository;
    private final ProfileImageCodeGenerator profileImageCodeGenerator;

    public OAuthLoginService(
            UserRepository userRepository,
            OAuthAccountRepository oAuthAccountRepository,
            NicknameCandidateRepository nicknameCandidateRepository,
            ProfileImageCodeGenerator profileImageCodeGenerator
    ) {
        this.userRepository = userRepository;
        this.oAuthAccountRepository = oAuthAccountRepository;
        this.nicknameCandidateRepository = nicknameCandidateRepository;
        this.profileImageCodeGenerator = profileImageCodeGenerator;
    }

    @Transactional
    public OAuthLoginResult login(OAuthLoginCommand command, Instant now) {
        Objects.requireNonNull(command, "command는 필수입니다.");
        Objects.requireNonNull(now, "now는 필수입니다.");

        Optional<OAuthAccount> existingAccount = findExistingAccount(command);

        if (existingAccount.isPresent()) {
            return loginExistingAccount(existingAccount.get(), command, now);
        }

        return createNewAccount(command, now);
    }

    private Optional<OAuthAccount> findExistingAccount(OAuthLoginCommand command) {

        return oAuthAccountRepository.
                findByProviderAndProviderSubject(command.provider(), command.providerSubject());
    }

    private OAuthLoginResult loginExistingAccount(OAuthAccount account, OAuthLoginCommand command, Instant now) {
        User user = account.getUser();
        validateActiveUser(user);

        user.updateNameIfProvided(command.displayName(), now);
        account.recordLogin(command.providerEmail(), now);

        return new OAuthLoginResult(user.getId());
    }

    private OAuthLoginResult createNewAccount(OAuthLoginCommand command, Instant now) {
        String nickname = nicknameCandidateRepository.lockNextAvailable();
        String displayName = command.displayName() == null ? nickname : command.displayName();
        RequiredOAuthProfile profile = new RequiredOAuthProfile(
                command.providerEmail(),
                displayName
        );

        User user = createUser(profile, nickname, now);
        createOAuthAccount(user, command, profile.email(), now);

        return new OAuthLoginResult(user.getId());
    }

    private User createUser(RequiredOAuthProfile profile, String nickname, Instant now) {
        int imageCode = profileImageCodeGenerator.generate();

        User user = new User(profile.email(), profile.displayName(), now);
        user.assignInitialPublicProfile(nickname, imageCode, now);

        User savedUser = userRepository.save(user);
        nicknameCandidateRepository.assignToUser(nickname, savedUser.getId());

        return savedUser;
    }

    private void createOAuthAccount(User user, OAuthLoginCommand command, String providerEmail, Instant now) {
        OAuthAccount account = new OAuthAccount(
                user,
                command.provider(),
                command.providerSubject(),
                providerEmail,
                now
        );

        oAuthAccountRepository.save(account);
    }

    private void validateActiveUser(User user) {
        if (!user.isDeleted()) {
            return;
        }

        throw new InactiveUserException();
    }

}
