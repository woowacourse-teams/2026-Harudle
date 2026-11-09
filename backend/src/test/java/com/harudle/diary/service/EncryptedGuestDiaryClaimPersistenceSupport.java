package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.diary.service.dto.CreateDiaryCommand;
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
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Common guest fixtures contain no dependency on the one-time conversion tool. */

abstract class EncryptedGuestDiaryClaimPersistenceSupport extends ServiceIntegrationTestSupport {

    protected static final LocalDate DATE = LocalDate.of(2026, 11, 6);
    protected static final String TEXT = "가입한 회원이 계속 읽을 수 있어야 하는 체험 일기 🙂";

    @Autowired
    protected GuestDiaryClaimService claimService;
    @Autowired
    protected DiaryCreationClaimService creationClaims;
    @Autowired
    protected DiaryQueryService queryService;
    @Autowired
    protected UserRepository users;
    @Autowired
    protected DiaryRepository diaries;
    @Autowired
    protected DiaryGenerationRepository generations;
    @Autowired
    protected GuestSessionRepository sessions;
    @Autowired
    protected GuestSessionTokenHasher tokenHasher;
    @Autowired
    protected DiarySourceTextCipher cipher;
    @Autowired
    protected RequestFingerprintGenerator fingerprints;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected DataSource dataSource;
    @Autowired
    protected TransactionTemplate transactions;

    protected final List<UUID> createdUsers = new ArrayList<>();
    protected Instant now;
    protected long promptId;

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

    protected void assertReplay(UUID memberId, Fixture guest) {
        DiaryCreationClaim replay = transactions.execute(status -> creationClaims.findExistingClaim(
                new CreateDiaryCommand(memberId, DATE, TEXT, guest.idempotencyKey())).orElseThrow());
        assertThat(replay).isNotNull();
        assertThat(replay.diaryId()).isEqualTo(guest.diaryId());
        assertThat(replay.newlyCreated()).isFalse();
        assertThat(replay.sourceText()).isEqualTo(TEXT);
    }

    protected Fixture fixture(int version) {
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

    protected User user(String name) {
        User user = users.saveAndFlush(new User(null, name, now));
        createdUsers.add(user.getId());
        return user;
    }

    protected GuestSession session(Fixture guest) {
        return sessions.findByTokenHash(tokenHasher.hash(guest.token())).orElseThrow();
    }

    protected String storedText(UUID diaryId) {
        return jdbc.queryForObject("SELECT source_text FROM diaries WHERE id = ?", String.class, diaryId);
    }

    protected Map<String, Object> persistedState(UUID diaryId) {
        return jdbc.queryForMap("""
                SELECT d.user_id, d.source_text, d.source_text_version, g.request_fingerprint
                  FROM diaries d JOIN diary_generations g ON g.diary_id = d.id WHERE d.id = ?
                """, diaryId);
    }

    protected record Fixture(UUID userId, UUID diaryId, UUID idempotencyKey, String token) {
    }
}
