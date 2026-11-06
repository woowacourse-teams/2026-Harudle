package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.infrastructure.crypto.DiarySourceTextCryptoException;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.exception.DiaryAccessDeniedException;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import com.harudle.generation.diary.service.dto.GenerateDiaryImageCommand;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import com.harudle.support.ServiceIntegrationTestSupport;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class EncryptedGuestDiaryClaimPersistenceTest extends ServiceIntegrationTestSupport {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 6);
    private static final String TEXT = "가입한 회원이 계속 읽을 수 있어야 하는 체험 일기 🙂";

    @Autowired
    private GuestDiaryClaimService claimService;
    @Autowired
    private DiaryCreationClaimService creationClaims;
    @Autowired
    private DiaryQueryService queryService;
    @Autowired
    private UserRepository users;
    @Autowired
    private DiaryRepository diaries;
    @Autowired
    private DiaryGenerationRepository generations;
    @Autowired
    private GuestSessionRepository sessions;
    @Autowired
    private GuestSessionTokenHasher tokenHasher;
    @Autowired
    private DiarySourceTextCipher cipher;
    @Autowired
    private RequestFingerprintGenerator fingerprints;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;

    private final List<UUID> createdUsers = new ArrayList<>();
    private Instant now;
    private long promptId;

    @BeforeEach
    void setUp() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        promptId = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('소유권 이전 회귀 테스트', '이미지 스타일', 'references/encrypted-claim.png') RETURNING id
                """, Long.class);
    }

    @AfterEach
    void cleanUp() {
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE id = ?", id));
        jdbc.update("DELETE FROM generation_prompts WHERE id = ?", promptId);
    }

    @Test
    @DisplayName("회원이 가져간 암호화 일기를 읽고 같은 요청으로 기존 생성 결과를 조회한다")
    void decryptAndReplayAfterEncryptedClaim() {
        Fixture guest = fixture(1);
        User member = user("회원");
        String original = storedText(guest.diaryId());

        var first = claimService.claim(member.getId(), guest.token());
        var second = claimService.claim(member.getId(), guest.token());

        assertThat(second).isEqualTo(first);
        assertThat(storedText(guest.diaryId())).isNotEqualTo(original).doesNotContain(TEXT);
        assertThat(queryService.getDetail(member.getId(), guest.diaryId()).sourceText()).isEqualTo(TEXT);
        assertThatThrownBy(() -> queryService.getDetail(guest.userId(), guest.diaryId()))
                .isExactlyInstanceOf(DiaryAccessDeniedException.class);
        assertThatThrownBy(() -> cipher.decrypt(guest.diaryId(), guest.userId(), storedText(guest.diaryId()), 1))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);
        assertReplay(member.getId(), guest);
    }

    @Test
    @DisplayName("전환 전 평문 체험 일기는 거절하고 소유자와 지문과 세션을 유지한다")
    void rejectLegacyClaimWithoutChangingOwnerOrSession() {
        Fixture guest = fixture(0);
        User member = user("기존 평문 회원");
        Map<String, Object> before = persistedState(guest.diaryId());

        assertThatThrownBy(() -> claimService.claim(member.getId(), guest.token()))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);

        assertThat(persistedState(guest.diaryId())).isEqualTo(before);
        assertThat(session(guest).isClaimed()).isFalse();
    }

    @Test
    @DisplayName("가져가기 저장 후 트랜잭션이 실패하면 소유자와 암호문과 지문과 세션이 모두 복원된다")
    void rollbackAllClaimChangesTogether() {
        Fixture guest = fixture(1);
        User member = user("롤백 회원");
        Map<String, Object> before = persistedState(guest.diaryId());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            claimService.claim(member.getId(), guest.token());
            assertThat(persistedState(guest.diaryId()).get("user_id")).isEqualTo(member.getId());
            throw new IllegalStateException("가져가기 이후 처리 실패");
        })).isExactlyInstanceOf(IllegalStateException.class);

        assertThat(persistedState(guest.diaryId())).isEqualTo(before);
        assertThat(session(guest).isClaimed()).isFalse();
        assertThat(queryService.getDetail(guest.userId(), guest.diaryId()).sourceText()).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("기존 암호문 인증 실패 시 소유권과 세션을 이전하지 않는다")
    void rejectTamperedCiphertextWithoutClaimingSession() {
        Fixture guest = fixture(1);
        User member = user("변조 거절 회원");
        String encryptedForAnotherDiary = cipher.encrypt(UUID.randomUUID(), guest.userId(), TEXT);
        jdbc.update("UPDATE diaries SET source_text = ? WHERE id = ?", encryptedForAnotherDiary, guest.diaryId());
        Map<String, Object> before = persistedState(guest.diaryId());

        assertThatThrownBy(() -> claimService.claim(member.getId(), guest.token()))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class);

        assertThat(persistedState(guest.diaryId())).isEqualTo(before);
        assertThat(session(guest).isClaimed()).isFalse();
    }

    private void assertReplay(UUID memberId, Fixture guest) {
        DiaryCreationClaim replay = transactions.execute(status -> creationClaims.findExistingClaim(
                new CreateDiaryCommand(memberId, DATE, TEXT, guest.idempotencyKey())).orElseThrow());
        assertThat(replay).isNotNull();
        assertThat(replay.diaryId()).isEqualTo(guest.diaryId());
        assertThat(replay.newlyCreated()).isFalse();
        assertThat(replay.sourceText()).isEqualTo(TEXT);
    }

    private Fixture fixture(int version) {
        User guest = user("게스트");
        Diary diary = diaries.saveAndFlush(Diary.create(guest.getId(), DATE, TEXT, cipher));
        UUID requestId = UUID.randomUUID();
        String canonical = fingerprints.generate(new GenerateDiaryImageCommand(
                guest.getId(), diary.getId(), DATE, TEXT, requestId));
        String protectedFingerprint = diary.protectRequestFingerprint(cipher, canonical);
        if (version == 0) {
            jdbc.update("UPDATE diaries SET source_text = ?, source_text_version = 0 WHERE id = ?", TEXT, diary.getId());
            protectedFingerprint = canonical;
        }
        DiaryGeneration generation = DiaryGeneration.pending(diary.getId(), promptId, requestId, protectedFingerprint, null);
        generation.startProcessing();
        generation.succeed(TestStoryboardFactory.create(), "generated/encrypted-claim-" + generation.getId() + ".png", now);
        generations.saveAndFlush(generation);
        String token = UUID.randomUUID().toString();
        GuestSession session = GuestSession.create(guest.getId(), tokenHasher.hash(token),
                now.plus(Duration.ofDays(30)), now.minus(Duration.ofHours(1)));
        session.useForDiary(diary.getId(), now.minusSeconds(30));
        sessions.saveAndFlush(session);
        return new Fixture(guest.getId(), diary.getId(), requestId, token);
    }

    private User user(String name) {
        User user = users.saveAndFlush(new User(null, name, now));
        createdUsers.add(user.getId());
        return user;
    }

    private GuestSession session(Fixture guest) {
        return sessions.findByTokenHash(tokenHasher.hash(guest.token())).orElseThrow();
    }

    private String storedText(UUID diaryId) {
        return jdbc.queryForObject("SELECT source_text FROM diaries WHERE id = ?", String.class, diaryId);
    }

    private Map<String, Object> persistedState(UUID diaryId) {
        return jdbc.queryForMap("""
                SELECT d.user_id, d.source_text, d.source_text_version, g.request_fingerprint
                  FROM diaries d JOIN diary_generations g ON g.diary_id = d.id WHERE d.id = ?
                """, diaryId);
    }

    private record Fixture(UUID userId, UUID diaryId, UUID idempotencyKey, String token) {
    }
}
