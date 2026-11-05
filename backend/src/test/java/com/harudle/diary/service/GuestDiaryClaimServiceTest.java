package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.application.InvalidCurrentUserException;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.GuestDiaryClaimResult;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationErrorCode;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.guest.application.exception.GuestDiaryAlreadyClaimedException;
import com.harudle.guest.application.exception.GuestDiaryClaimForbiddenException;
import com.harudle.guest.application.exception.GuestDiaryClaimLimitExceededException;
import com.harudle.guest.application.exception.GuestDiaryClaimNotFoundException;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Transactional
class GuestDiaryClaimServiceTest extends ServiceIntegrationTestSupport {

    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 11, 5);

    @Autowired
    private GuestDiaryClaimService claimService;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private GuestSessionTokenHasher tokenHasher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DiaryRepository diaryRepository;

    @Autowired
    private DiaryGenerationRepository diaryGenerationRepository;

    @PersistenceContext
    private EntityManager entityManager;

    private Instant now;
    private long promptId;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        promptId = insertGenerationPrompt();
    }

    @Test
    @DisplayName("게스트 체험 일기를 회원에게 옮기고 세션에 가져간 회원을 기록한다")
    void claimGuestDiary() {
        User member = saveUser("회원");
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.PENDING);

        GuestDiaryClaimResult result = claimService.claim(member.getId(), "guest-token");

        assertThat(result.diaryId()).isEqualTo(guest.diaryId());
        assertThat(result.diaryDate()).isEqualTo(DIARY_DATE);
        assertThat(result.generationStatus()).isEqualTo(GenerationStatus.PENDING);
        assertThat(result.claimedAt()).isBetween(now, Instant.now());
        entityManager.flush();
        entityManager.clear();
        assertThat(diaryRepository.findById(guest.diaryId()).orElseThrow().getUserId()).isEqualTo(member.getId());
        GuestSession session = findSession("guest-token");
        assertThat(session.isClaimedBy(member.getId())).isTrue();
        assertThat(session.getClaimedAt()).isEqualTo(result.claimedAt());
    }

    @ParameterizedTest
    @EnumSource(value = GenerationStatus.class, names = {"PENDING", "PROCESSING", "SUCCEEDED"})
    @DisplayName("생성 중이거나 완료된 일기를 현재 생성 상태와 함께 가져간다")
    void claimDiaryInAnyActiveGenerationStatus(GenerationStatus status) {
        User member = saveUser("회원");
        GuestFixture guest = saveGuestWithDiary("guest-token", status);

        GuestDiaryClaimResult result = claimService.claim(member.getId(), "guest-token");

        assertThat(result.generationStatus()).isEqualTo(status);
        assertThat(diaryRepository.findById(guest.diaryId()).orElseThrow().getUserId()).isEqualTo(member.getId());
        assertThat(diaryGenerationRepository.findById(guest.generationId()).orElseThrow().getRequestedByUserId())
                .isEqualTo(guest.guestUserId());
    }

    @Test
    @DisplayName("같은 회원이 다시 요청하면 처음 가져간 결과를 그대로 돌려준다")
    void returnSameResultForSameMember() {
        User member = saveUser("회원");
        saveGuestWithDiary("guest-token", GenerationStatus.PROCESSING);
        GuestDiaryClaimResult first = claimService.claim(member.getId(), "guest-token");

        GuestDiaryClaimResult second = claimService.claim(member.getId(), "guest-token");

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("다른 회원이 이미 가져간 체험 일기는 가져갈 수 없다")
    void rejectDiaryClaimedByAnotherMember() {
        User member = saveUser("회원");
        User anotherMember = saveUser("다른 회원");
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.SUCCEEDED);
        claimService.claim(member.getId(), "guest-token");

        assertThatThrownBy(() -> claimService.claim(anotherMember.getId(), "guest-token"))
                .isInstanceOf(GuestDiaryAlreadyClaimedException.class);
        assertThat(diaryRepository.findById(guest.diaryId()).orElseThrow().getUserId()).isEqualTo(member.getId());
    }

    @Test
    @DisplayName("회원은 체험 일기를 한 번만 가져갈 수 있다")
    void rejectSecondClaimBySameMember() {
        User member = saveUser("회원");
        saveGuestWithDiary("first-token", GenerationStatus.SUCCEEDED);
        GuestFixture second = saveGuestWithDiary("second-token", GenerationStatus.SUCCEEDED);
        claimService.claim(member.getId(), "first-token");

        assertThatThrownBy(() -> claimService.claim(member.getId(), "second-token"))
                .isInstanceOf(GuestDiaryClaimLimitExceededException.class);
        assertThat(diaryRepository.findById(second.diaryId()).orElseThrow().getUserId())
                .isEqualTo(second.guestUserId());
        assertThat(findSession("second-token").isClaimed()).isFalse();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("게스트 세션 Cookie 값이 없으면 가져갈 일기가 없다고 응답한다")
    void rejectMissingToken(String rawToken) {
        User member = saveUser("회원");

        assertThatThrownBy(() -> claimService.claim(member.getId(), rawToken))
                .isInstanceOf(GuestDiaryClaimNotFoundException.class);
    }

    @Test
    @DisplayName("저장되지 않은 게스트 세션이면 가져갈 일기가 없다고 응답한다")
    void rejectUnknownToken() {
        User member = saveUser("회원");

        assertThatThrownBy(() -> claimService.claim(member.getId(), "unknown-token"))
                .isInstanceOf(GuestDiaryClaimNotFoundException.class);
    }

    @Test
    @DisplayName("만료된 게스트 세션이면 가져갈 일기가 없다고 응답한다")
    void rejectExpiredSession() {
        User member = saveUser("회원");
        User guestUser = saveUser("게스트");
        Diary diary = diaryRepository.saveAndFlush(Diary.create(guestUser.getId(), DIARY_DATE, "만료된 체험 일기"));
        saveGeneration(diary, guestUser.getId(), GenerationStatus.SUCCEEDED);
        GuestSession session = GuestSession.create(
                guestUser.getId(),
                tokenHasher.hash("expired-token"),
                now.minus(Duration.ofDays(1)),
                now.minus(Duration.ofDays(31))
        );
        session.useForDiary(diary.getId(), now.minus(Duration.ofDays(30)));
        guestSessionRepository.saveAndFlush(session);

        assertThatThrownBy(() -> claimService.claim(member.getId(), "expired-token"))
                .isInstanceOf(GuestDiaryClaimNotFoundException.class);
    }

    @Test
    @DisplayName("일기를 작성하지 않은 게스트 세션이면 가져갈 일기가 없다고 응답한다")
    void rejectSessionWithoutDiary() {
        User member = saveUser("회원");
        User guestUser = saveUser("게스트");
        guestSessionRepository.saveAndFlush(GuestSession.create(
                guestUser.getId(),
                tokenHasher.hash("unused-token"),
                now.plus(Duration.ofDays(30)),
                now.minus(Duration.ofHours(1))
        ));

        assertThatThrownBy(() -> claimService.claim(member.getId(), "unused-token"))
                .isInstanceOf(GuestDiaryClaimNotFoundException.class);
    }

    @Test
    @DisplayName("생성에 실패해 숨겨진 일기는 가져갈 수 없다")
    void rejectFailedGenerationDiary() {
        User member = saveUser("회원");
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.FAILED);

        assertThatThrownBy(() -> claimService.claim(member.getId(), "guest-token"))
                .isInstanceOf(GuestDiaryClaimNotFoundException.class);
        assertThat(findSession("guest-token").isClaimed()).isFalse();
        assertThat(diaryRepository.findById(guest.diaryId()).orElseThrow().getUserId())
                .isEqualTo(guest.guestUserId());
    }

    @Test
    @DisplayName("게스트 사용자 본인 ID로는 체험 일기를 가져갈 수 없다")
    void rejectGuestUserItself() {
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.SUCCEEDED);

        assertThatThrownBy(() -> claimService.claim(guest.guestUserId(), "guest-token"))
                .isInstanceOf(GuestDiaryClaimForbiddenException.class);
    }

    @Test
    @DisplayName("존재하지 않는 회원에게는 체험 일기를 옮기지 않는다")
    void rejectMissingMember() {
        GuestFixture guest = saveGuestWithDiary("guest-token", GenerationStatus.SUCCEEDED);

        assertThatThrownBy(() -> claimService.claim(UUID.randomUUID(), "guest-token"))
                .isInstanceOf(InvalidCurrentUserException.class);
        assertThat(findSession("guest-token").isClaimed()).isFalse();
        assertThat(diaryRepository.findById(guest.diaryId()).orElseThrow().getUserId())
                .isEqualTo(guest.guestUserId());
    }

    private GuestFixture saveGuestWithDiary(String rawToken, GenerationStatus status) {
        User guestUser = saveUser("게스트");
        Diary diary = diaryRepository.saveAndFlush(Diary.create(guestUser.getId(), DIARY_DATE, "게스트 체험 일기"));
        DiaryGeneration generation = saveGeneration(diary, guestUser.getId(), status);
        GuestSession session = GuestSession.create(
                guestUser.getId(),
                tokenHasher.hash(rawToken),
                now.plus(Duration.ofDays(30)),
                now.minus(Duration.ofHours(1))
        );
        session.useForDiary(diary.getId(), now.minus(Duration.ofMinutes(30)));
        guestSessionRepository.saveAndFlush(session);
        return new GuestFixture(guestUser.getId(), diary.getId(), generation.getId());
    }

    private DiaryGeneration saveGeneration(Diary diary, UUID requesterId, GenerationStatus status) {
        DiaryGeneration generation = DiaryGeneration.pending(
                diary.getId(), requesterId, promptId, UUID.randomUUID(), "a".repeat(64), null
        );
        if (status != GenerationStatus.PENDING) {
            generation.startProcessing();
        }
        if (status == GenerationStatus.SUCCEEDED) {
            generation.succeed(TestStoryboardFactory.create(), "generated/" + generation.getId() + ".png", now);
        }
        if (status == GenerationStatus.FAILED) {
            generation.fail(GenerationErrorCode.GENERATION_INTERRUPTED, now);
            diary.delete(now);
            diaryRepository.saveAndFlush(diary);
        }
        return diaryGenerationRepository.saveAndFlush(generation);
    }

    private User saveUser(String name) {
        return userRepository.saveAndFlush(new User(null, name, now));
    }

    private GuestSession findSession(String rawToken) {
        return guestSessionRepository.findByTokenHash(tokenHasher.hash(rawToken)).orElseThrow();
    }

    private long insertGenerationPrompt() {
        return ((Number) entityManager.createNativeQuery("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text, image_style_prompt_text, image_asset_object_key
                )
                VALUES ('스토리보드 프롬프트', '이미지 스타일 프롬프트', 'references/claim-style.png')
                RETURNING id
                """).getSingleResult()).longValue();
    }

    private record GuestFixture(UUID guestUserId, UUID diaryId, UUID generationId) {
    }
}
