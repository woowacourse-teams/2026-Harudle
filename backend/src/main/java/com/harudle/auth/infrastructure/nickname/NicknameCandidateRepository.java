package com.harudle.auth.infrastructure.nickname;

import com.harudle.auth.domain.exception.NicknamePoolBusyException;
import com.harudle.auth.domain.exception.NicknamePoolExhaustedException;
import com.harudle.auth.domain.policy.NicknamePolicy;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class NicknameCandidateRepository {

    private final NicknameCandidateJpaRepository jpaRepository;

    public NicknameCandidateRepository(NicknameCandidateJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    public String lockNextAvailable() {
        var candidate = jpaRepository.lockNextAvailableNickname();

        if (candidate.isPresent()) {
            return candidate.get();
        }

        if (jpaRepository.existsAvailableNickname()) {
            throw new NicknamePoolBusyException();
        }

        throw new NicknamePoolExhaustedException();
    }

    public void assignToUser(String rawNickname, UUID userId) {
        String nickname = NicknamePolicy.normalizeAndValidate(rawNickname);
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");

        if (jpaRepository.assignToUser(nickname, userId) != 1) {
            throw new IllegalStateException("회원의 현재 닉네임과 후보 점유를 일치시킬 수 없습니다: " + nickname);
        }
    }

    public boolean releaseFromUser(String rawNickname, UUID userId) {
        String nickname = NicknamePolicy.normalizeAndValidate(rawNickname);
        Objects.requireNonNull(userId, "회원 ID는 필수입니다.");

        return jpaRepository.releaseFromUser(nickname, userId) == 1;
    }
}
