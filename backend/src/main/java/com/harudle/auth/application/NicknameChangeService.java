package com.harudle.auth.application;

import com.harudle.auth.domain.User;
import com.harudle.auth.domain.exception.NicknameAlreadyInUseException;
import com.harudle.auth.domain.exception.PublicProfileNotAssignedException;
import com.harudle.auth.domain.policy.NicknamePolicy;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.nickname.NicknameCandidateRepository;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NicknameChangeService {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final NicknameCandidateRepository candidateRepository;

    public NicknameChangeService(
            UserRepository userRepository,
            OAuthAccountRepository oauthAccountRepository,
            NicknameCandidateRepository candidateRepository
    ) {
        this.userRepository = userRepository;
        this.oauthAccountRepository = oauthAccountRepository;
        this.candidateRepository = candidateRepository;
    }

    @Transactional
    public void change(UUID userId, String rawNickname) {
        Objects.requireNonNull(userId, "userId는 필수입니다.");
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(InvalidCurrentUserException::new);

        if (user.isDeleted() || oauthAccountRepository.findByUser_Id(userId).isEmpty()) {
            throw new InvalidCurrentUserException();
        }
        if (user.getNickname() == null || user.getProfileImageCode() == null) {
            throw new PublicProfileNotAssignedException();
        }

        String nickname = NicknamePolicy.normalizeAndValidate(rawNickname);
        String previousNickname = user.getNickname();

        if (nickname.equals(previousNickname)) {
            return;
        }
        if (userRepository.existsByNicknameAndDeletedAtIsNull(nickname)) {
            throw new NicknameAlreadyInUseException();
        }

        boolean candidate = candidateRepository.lockForChange(nickname, userId);
        user.changeNickname(nickname, Instant.now());
        flushNicknameChange();
        candidateRepository.releaseFromUser(previousNickname, userId);

        if (candidate) {
            candidateRepository.assignToUser(nickname, userId);
        }
    }

    private void flushNicknameChange() {
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException violation
                        && UNIQUE_VIOLATION_SQL_STATE.equals(violation.getSQLState())
                        && "uq_users_active_nickname".equals(violation.getConstraintName())) {
                    throw new NicknameAlreadyInUseException(exception);
                }
            }
            throw exception;
        }
    }
}
