package com.harudle.share.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.share.service.exception.ShareNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ShareLinkQueryServiceTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final UUID USER_ID = UUID.fromString("17c1414e-40c6-4479-9025-3bdb2a08a09a");
    private static final UUID DIARY_ID = UUID.fromString("1357d730-a710-4a94-9bee-494b569c3779");
    private static final UUID GENERATION_ID = UUID.fromString("9a08ce23-fb30-4744-a9d2-1c3b9cc8796a");
    private static final UUID SHARE_ID = UUID.fromString("3ab878d7-abba-43d7-87cb-5a771a86587b");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 6);
    private static final Instant DIARY_CREATED_AT = Instant.parse("2026-08-06T11:10:23Z");

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ShareLinkQueryService shareLinkQueryService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        executeUpdate(
                "INSERT INTO users (id, primary_email, name) VALUES (?, ?, ?)",
                USER_ID,
                "public-share@example.com",
                "공개 공유 사용자"
        );
        executeUpdate("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text,
                    image_style_prompt_text,
                    image_asset_object_key
                ) VALUES (?, ?, ?)
                """, "스토리보드 프롬프트", "이미지 스타일 프롬프트", "references/public-share.png");
        executeUpdate("""
                INSERT INTO diaries (
                    id, user_id, diary_date, source_text, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """, DIARY_ID, USER_ID, DIARY_DATE, "외부에 공개하면 안 되는 원문", DIARY_CREATED_AT, DIARY_CREATED_AT);
        executeUpdate("""
                INSERT INTO diary_generations (
                    id,
                    diary_id,
                    prompt_id,
                    idempotency_key,
                    request_fingerprint,
                    status,
                    title,
                    image_object_key,
                    completed_at
                ) VALUES (
                    ?,
                    ?,
                    (SELECT id FROM generation_prompts WHERE image_asset_object_key = ?),
                    ?,
                    ?,
                    'SUCCEEDED',
                    ?,
                    ?,
                    CURRENT_TIMESTAMP
                )
                """,
                GENERATION_ID,
                DIARY_ID,
                "references/public-share.png",
                UUID.randomUUID(),
                "b".repeat(64),
                "친구와 보낸 카페 시간",
                "generated/public-share.png"
        );
        executeUpdate(
                "INSERT INTO share_links (id, generation_id) VALUES (?, ?)",
                SHARE_ID,
                GENERATION_ID
        );
    }

    @AfterEach
    void tearDown() {
        executeUpdate("DELETE FROM share_links WHERE id = ?", SHARE_ID);
        executeUpdate("DELETE FROM diary_generations WHERE id = ?", GENERATION_ID);
        executeUpdate("DELETE FROM diaries WHERE id = ?", DIARY_ID);
        executeUpdate(
                "DELETE FROM generation_prompts WHERE image_asset_object_key = ?",
                "references/public-share.png"
        );
        executeUpdate("DELETE FROM users WHERE id = ?", USER_ID);
    }

    @Test
    @DisplayName("공유 ID로 공개 가능한 그림일기 정보만 조회한다")
    void getPublicShare() {
        PublicShareResult result = shareLinkQueryService.getPublicShare(SHARE_ID);

        assertThat(result.title()).isEqualTo("친구와 보낸 카페 시간");
        assertThat(result.diaryDate()).isEqualTo(DIARY_DATE);
        assertThat(result.imageObjectKey()).isEqualTo("generated/public-share.png");
        assertThat(result.createdAt()).isEqualTo(DIARY_CREATED_AT);
    }

    @Test
    @DisplayName("공유 링크가 없으면 공개 결과를 조회할 수 없다")
    void failWhenShareLinkDoesNotExist() {
        UUID unknownShareId = UUID.randomUUID();

        assertThatThrownBy(() -> shareLinkQueryService.getPublicShare(unknownShareId))
                .isInstanceOf(ShareNotFoundException.class);
    }

    @Test
    @DisplayName("연결된 그림일기가 삭제되면 공개 결과를 조회할 수 없다")
    void failWhenDiaryIsDeleted() {
        executeUpdate("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", DIARY_ID);

        assertThatThrownBy(() -> shareLinkQueryService.getPublicShare(SHARE_ID))
                .isInstanceOf(ShareNotFoundException.class);
    }

    private void executeUpdate(String statement, Object... parameters) {
        transactionTemplate.executeWithoutResult(status -> {
            Query query = entityManager.createNativeQuery(statement);
            IntStream.range(0, parameters.length)
                    .forEach(index -> query.setParameter(index + 1, parameters[index]));
            query.executeUpdate();
        });
    }
}
