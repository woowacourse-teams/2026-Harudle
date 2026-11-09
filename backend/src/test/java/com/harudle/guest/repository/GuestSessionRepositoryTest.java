package com.harudle.guest.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.guest.domain.GuestSession;
import com.harudle.support.ServiceIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Transactional
class GuestSessionRepositoryTest extends ServiceIntegrationTestSupport {

    private static final String TOKEN_HASH = "a".repeat(64);
    private static final String ANOTHER_TOKEN_HASH = "b".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");
    private static final Instant USED_AT = Instant.parse("2026-08-19T01:00:00Z");
    private static final Instant CLAIMED_AT = Instant.parse("2026-08-19T02:00:00Z");

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private DiaryRepository diaryRepository;

    @Autowired
    private DiarySourceTextCipher cipher;

    @Autowired
    private UserRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @DisplayName("게스트 세션을 저장하고 토큰 해시로 조회한다")
    void savesAndFindsByTokenHash() {
        User guestUser = saveGuestUser("게스트1");

        GuestSession session = GuestSession.create(
                guestUser.getId(),
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        GuestSession savedSession =
                guestSessionRepository.saveAndFlush(session);

        UUID savedSessionId = savedSession.getId();

        entityManager.clear();

        GuestSession foundSession = guestSessionRepository
                .findByTokenHash(TOKEN_HASH)
                .orElseThrow();

        assertThat(foundSession.getId()).isEqualTo(savedSessionId);
        assertThat(foundSession.getGuestUserId()).isEqualTo(guestUser.getId());
        assertThat(foundSession.getTokenHash()).isEqualTo(TOKEN_HASH);
        assertThat(foundSession.getDiaryId()).isNull();
        assertThat(foundSession.getUsedAt()).isNull();
        assertThat(foundSession.getExpiresAt()).isEqualTo(EXPIRES_AT);
        assertThat(foundSession.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    @DisplayName("존재하지 않는 토큰 해시는 조회되지 않는다")
    void returnsEmptyWhenTokenHashDoesNotExist() {
        assertThat(
                guestSessionRepository.findByTokenHash(TOKEN_HASH)
        ).isEmpty();
    }

    @Test
    @DisplayName("토큰 해시로 게스트 세션을 잠금 조회한다")
    void findsByTokenHashForUpdate() {
        User guestUser = saveGuestUser("게스트1");
        GuestSession savedSession = guestSessionRepository.saveAndFlush(
                GuestSession.create(
                        guestUser.getId(),
                        TOKEN_HASH,
                        EXPIRES_AT,
                        CREATED_AT
                )
        );
        UUID savedSessionId = savedSession.getId();
        entityManager.clear();

        GuestSession foundSession = guestSessionRepository
                .findByTokenHashForUpdate(TOKEN_HASH)
                .orElseThrow();

        assertThat(foundSession.getId()).isEqualTo(savedSessionId);
        assertThat(foundSession.getTokenHash()).isEqualTo(TOKEN_HASH);
    }

    @Test
    @DisplayName("같은 토큰 해시를 중복 저장할 수 없다")
    void rejectsDuplicateTokenHash() {
        User firstGuestUser = saveGuestUser("게스트1");
        User secondGuestUser = saveGuestUser("게스트2");

        guestSessionRepository.saveAndFlush(
                GuestSession.create(
                        firstGuestUser.getId(),
                        TOKEN_HASH,
                        EXPIRES_AT,
                        CREATED_AT
                )
        );

        GuestSession duplicatedSession = GuestSession.create(
                secondGuestUser.getId(),
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        assertThatThrownBy(() ->
                guestSessionRepository.saveAndFlush(duplicatedSession)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("하나의 게스트 사용자는 하나의 세션만 가질 수 있다")
    void rejectsDuplicateGuestUser() {
        User guestUser = saveGuestUser("게스트1");

        guestSessionRepository.saveAndFlush(
                GuestSession.create(
                        guestUser.getId(),
                        TOKEN_HASH,
                        EXPIRES_AT,
                        CREATED_AT
                )
        );

        GuestSession duplicatedSession = GuestSession.create(
                guestUser.getId(),
                ANOTHER_TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        assertThatThrownBy(() ->
                guestSessionRepository.saveAndFlush(duplicatedSession)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 게스트 사용자로 세션을 저장할 수 없다")
    void rejectsMissingGuestUser() {
        GuestSession session = GuestSession.create(
                UUID.randomUUID(),
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        );

        assertThatThrownBy(() ->
                guestSessionRepository.saveAndFlush(session)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("회원이 가져간 게스트 세션의 회원 ID와 시각을 저장한다")
    void savesClaimedSession() {
        User member = saveGuestUser("회원1");
        GuestSession session = saveUsedSession("게스트1", TOKEN_HASH);
        session.claim(member.getId(), CLAIMED_AT);
        guestSessionRepository.saveAndFlush(session);
        entityManager.clear();

        GuestSession foundSession = guestSessionRepository.findByTokenHash(TOKEN_HASH).orElseThrow();

        assertThat(foundSession.isClaimedBy(member.getId())).isTrue();
        assertThat(foundSession.getClaimedAt()).isEqualTo(CLAIMED_AT);
    }

    @Test
    @DisplayName("한 회원은 게스트 세션을 하나만 가져갈 수 있다")
    void rejectsSecondClaimBySameMember() {
        User member = saveGuestUser("회원1");
        GuestSession firstSession = saveUsedSession("게스트1", TOKEN_HASH);
        firstSession.claim(member.getId(), CLAIMED_AT);
        guestSessionRepository.saveAndFlush(firstSession);
        GuestSession secondSession = saveUsedSession("게스트2", ANOTHER_TOKEN_HASH);
        secondSession.claim(member.getId(), CLAIMED_AT);

        assertThatThrownBy(() -> guestSessionRepository.saveAndFlush(secondSession))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("가져간 회원이 삭제되면 회원 ID만 지우고 가져간 시각은 남긴다")
    void keepsClaimedAtWhenClaimingMemberIsDeleted() {
        User member = saveGuestUser("회원1");
        GuestSession session = saveUsedSession("게스트1", TOKEN_HASH);
        Diary diary = diaryRepository.findById(session.getDiaryId()).orElseThrow();
        diary.transferOwnership(session.getGuestUserId(), member.getId(), cipher);
        session.claim(member.getId(), CLAIMED_AT);
        entityManager.flush();
        entityManager.clear();

        userRepository.deleteById(member.getId());
        entityManager.flush();
        entityManager.clear();

        GuestSession foundSession = guestSessionRepository.findByTokenHash(TOKEN_HASH).orElseThrow();
        assertThat(foundSession.getClaimedByUserId()).isNull();
        assertThat(foundSession.getClaimedAt()).isEqualTo(CLAIMED_AT);
        assertThat(foundSession.isClaimed()).isTrue();
        assertThat(foundSession.getDiaryId()).isNull();
        assertThat(diaryRepository.findById(diary.getId())).isEmpty();
    }

    private GuestSession saveUsedSession(String guestName, String tokenHash) {
        User guestUser = saveGuestUser(guestName);
        Diary diary = diaryRepository.saveAndFlush(
                Diary.create(guestUser.getId(), LocalDate.of(2026, 8, 19), "게스트 체험 일기", cipher)
        );
        GuestSession session = GuestSession.create(guestUser.getId(), tokenHash, EXPIRES_AT, CREATED_AT);
        session.useForDiary(diary.getId(), USED_AT);
        return guestSessionRepository.saveAndFlush(session);
    }

    private User saveGuestUser(String name) {
        return userRepository.saveAndFlush(
                new User(
                        null,
                        name,
                        CREATED_AT
                )
        );
    }
}
