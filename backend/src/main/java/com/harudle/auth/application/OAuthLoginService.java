package com.harudle.auth.application;

import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OAuthLoginService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oAuthAccountRepository;

    public OAuthLoginService(UserRepository userRepository, OAuthAccountRepository oAuthAccountRepository) {
        this.userRepository = userRepository;
        this.oAuthAccountRepository = oAuthAccountRepository;
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

        account.recordLogin(command.providerEmail(), now);

        return new OAuthLoginResult(user.getId());
    }

    private OAuthLoginResult createNewAccount(OAuthLoginCommand command, Instant now) {
        User user = createUser(command, now);
        createOAuthAccount(user, command, now);

        return new OAuthLoginResult(user.getId());
    }

    private User createUser(OAuthLoginCommand command, Instant now) {
        User user = new User(command.providerEmail(), command.displayName(), now);

        return userRepository.save(user);
    }

    private void createOAuthAccount(User user, OAuthLoginCommand command, Instant now) {
        OAuthAccount account = new OAuthAccount(
                user,
                command.provider(),
                command.providerSubject(),
                command.providerEmail(),
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
