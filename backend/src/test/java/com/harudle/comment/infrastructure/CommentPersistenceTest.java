package com.harudle.comment.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.TestcontainersConfiguration;
import com.harudle.comment.repository.CommentRepository;
import com.harudle.comment.service.CommentService;
import com.harudle.comment.service.exception.CommentNotFoundException;
import com.harudle.diary.service.DiaryDeletionService;
import com.harudle.feed.service.FeedDeletionService;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.profile.service.DefaultPublicProfileReader;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "harudle.generation.adapters.enabled=false",
        "harudle.profile.image-base-url=https://test.example.com/profiles"
})
@AutoConfigureMockMvc
class CommentPersistenceTest {

    private static final String CSRF_TOKEN = "comment-cookie-and-header-value";
    private static final Instant FIXTURE_TIME = Instant.parse("2026-10-12T03:00:00Z");
    private static final int WORKERS = 8;

    @Autowired
    private CommentService service;

    @Autowired
    private CommentRepository comments;

    @Autowired
    private DiaryDeletionService diaryDeletion;

    @Autowired
    private FeedDeletionService feedDeletion;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private DefaultPublicProfileReader profileReader;

    private final ProfileReadProbe profiles = new ProfileReadProbe();

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private FeedPushOutbox outbox;

    private final List<UUID> fixtureUsers = new ArrayList<>();
    private UUID feedAuthorId;
    private UUID commenterId;
    private UUID diaryId;
    private UUID feedId;

    @BeforeEach
    void setUp() {
        profiles.reset();
        doAnswer(invocation -> {
            profiles.recordRead(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(profileReader).readAll(anySet());
        feedAuthorId = createMember();
        commenterId = createMember();
        diaryId = createDiary(feedAuthorId);
        feedId = createFeed(diaryId);
        when(jwtDecoder.decode("member-token")).thenReturn(jwt("member-token", commenterId));
        when(jwtDecoder.decode("owner-token")).thenReturn(jwt("owner-token", feedAuthorId));
    }

    @AfterEach
    void tearDown() {
        profiles.reset();
        for (UUID userId : fixtureUsers) {
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
        fixtureUsers.clear();
    }

    @Test
    @DisplayName("공개 댓글 GET은 익명에게 본인 여부를 숨기고 유효한 JWT에는 실제 본인 여부를 반환한다")
    void publicListPersonalizesOnlyAuthenticatedViewer() throws Exception {
        var own = service.create(commenterId, feedId, "내 댓글");
        var other = service.create(feedAuthorId, feedId, "피드 작성자의 댓글");

        mockMvc.perform(get(commentsPath()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(own.id().toString()))
                .andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.items[1].isMine").value(false))
                .andExpect(jsonPath("$.nextCursor").value((Object) null))
                .andExpect(jsonPath("$.hasNext").value(false));
        mockMvc.perform(bearer(get(commentsPath())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].isMine").value(true))
                .andExpect(jsonPath("$.items[1].id").value(other.id().toString()))
                .andExpect(jsonPath("$.items[1].isMine").value(false));
    }

    @Test
    @DisplayName("실제 HTTP 작성은 앞뒤 공백을 제거한 100개 이모지와 정확한 댓글 DTO를 반환한다")
    void createNormalizedHundredCodePointCommentThroughHttp() throws Exception {
        String emoji = "😀".repeat(100);

        mockMvc.perform(withCsrf(bearer(post(commentsPath())))
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody(" \n" + emoji + "\t ")))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.*", hasSize(5)))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.author.id").value(commenterId.toString()))
                .andExpect(jsonPath("$.author.profileImageUrl").value("https://test.example.com/profiles/1.png"))
                .andExpect(jsonPath("$.content").value(emoji))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.likeCount").doesNotExist())
                .andExpect(jsonPath("$.commentCount").doesNotExist());

        assertState(1, 1);
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE feed_id = ?", String.class, feedId))
                .isEqualTo(emoji);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "\u2003\u2003", "\u3000"})
    @DisplayName("공백만 있는 댓글은 실제 HTTP에서 content 필드 오류로 거절하고 아무것도 저장하지 않는다")
    void rejectBlankCommentsThroughHttp(String blank) throws Exception {
        mockMvc.perform(withCsrf(bearer(post(commentsPath())))
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody(blank)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        assertState(0, 0);
    }

    @Test
    @DisplayName("101개 코드 포인트와 누락·null 댓글은 필드 오류이며 100개 BMP 글자는 성공한다")
    void enforceContentBoundaryAndRequiredField() throws Exception {
        for (String body : List.of(commentBody("😀".repeat(101)), "{}", "{\"content\":null}")) {
            mockMvc.perform(withCsrf(bearer(post(commentsPath())))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.errors[0].field").value("content"));
        }
        assertState(0, 0);
        mockMvc.perform(withCsrf(bearer(post(commentsPath())))
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody("가".repeat(100))))
                .andExpect(status().isCreated());
        assertState(1, 1);
    }

    @Test
    @DisplayName("PostgreSQL 텍스트에 저장할 수 없는 NUL을 요청 단계에서 필드 오류로 거절한다")
    void rejectNulInContentBeforeDatabaseWrite() throws Exception {
        mockMvc.perform(withCsrf(bearer(post(commentsPath())))
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody("댓글" + (char) 0 + "내용")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("content"));
        assertState(0, 0);
    }

    @Test
    @DisplayName("실제 HTTP에서 인증 또는 CSRF가 없으면 댓글·개수·알림을 변경하지 않는다")
    void rejectUnauthorizedAndMissingCsrfWrites() throws Exception {
        mockMvc.perform(post(commentsPath()).contentType(MediaType.APPLICATION_JSON).content(commentBody("댓글")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(bearer(post(commentsPath()))
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody("댓글")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"));
        assertState(0, 0);
    }

    @Test
    @DisplayName("JWT와 CSRF가 유효해도 OAuth 회원이 아니면 댓글 작성은 401이다")
    void rejectGuestUserEvenWithValidJwtAndCsrf() throws Exception {
        UUID guest = createUser();
        when(jwtDecoder.decode("guest-token")).thenReturn(jwt("guest-token", guest));
        mockMvc.perform(withCsrf(post(commentsPath())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer guest-token"))
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody("게스트 댓글")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertState(0, 0);
    }

    @Test
    @DisplayName("삭제 권한은 댓글 작성자에게만 있고 본인 삭제 후 반복 삭제는 404다")
    void deleteOnlyOwnCommentThroughHttp() throws Exception {
        UUID comment = service.create(commenterId, feedId, "내 댓글").id();
        mockMvc.perform(withCsrf(delete(commentPath(comment))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer owner-token")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertState(1, 1);

        mockMvc.perform(withCsrf(bearer(delete(commentPath(comment)))))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertState(0, 1);
        mockMvc.perform(withCsrf(bearer(delete(commentPath(comment)))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
        assertState(0, 1);
    }

    @Test
    @DisplayName("빈 공개 피드는 빈 페이지지만 삭제된 피드와 삭제 원본의 댓글 조회는 404다")
    void distinguishEmptyFeedAndUnavailableParents() throws Exception {
        mockMvc.perform(get(commentsPath()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
        assertThat(profiles.callCount()).isZero();
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feedId);
        mockMvc.perform(get(commentsPath())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));

        UUID anotherDiary = createDiary(feedAuthorId);
        UUID anotherFeed = createFeed(anotherDiary);
        jdbc.update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", anotherDiary);
        mockMvc.perform(get("/api/v1/feeds/" + anotherFeed + "/comments"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
    }

    @Test
    @DisplayName("피드 작성자가 탈퇴하면 남은 댓글은 공개하지 않고 댓글 쓰기와 삭제도 원본을 변경하지 않는다")
    void hideCommentsOfFeedWhoseAuthorWasDeleted() throws Exception {
        UUID comment = service.create(commenterId, feedId, "탈퇴 전 작성된 다른 회원의 댓글").id();
        UUID notification = uuidValue("SELECT id FROM notifications WHERE feed_id = ?", feedId);
        profiles.resetCallCount();
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feedAuthorId);

        assertThat(comments.existsVisibleFeed(feedId)).isFalse();
        assertThat(comments.findAfter(feedId, Optional.empty(), 20)).isEmpty();
        mockMvc.perform(get(commentsPath())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
        assertThat(profiles.callCount()).isZero();

        mockMvc.perform(withCsrf(bearer(post(commentsPath())))
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody("추가할 수 없는 댓글")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
        assertState(1, 1);
        mockMvc.perform(withCsrf(bearer(delete(commentPath(comment)))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));

        assertState(1, 1);
        assertThat(uuidValue("SELECT id FROM comments WHERE feed_id = ? AND deleted_at IS NULL", feedId))
                .isEqualTo(comment);
        assertThat(jdbc.queryForObject("SELECT content FROM comments WHERE id = ?", String.class, comment))
                .isEqualTo("탈퇴 전 작성된 다른 회원의 댓글");
        assertThat(uuidValue("SELECT id FROM notifications WHERE feed_id = ?", feedId)).isEqualTo(notification);
        assertThat(uuidValue("SELECT source_action_id FROM notifications WHERE id = ?", notification))
                .isEqualTo(comment);
        assertThat(intValue("SELECT count(*) FROM feeds WHERE id = ? AND deleted_at IS NULL", feedId)).isEqualTo(1);
        assertThat(intValue("SELECT count(*) FROM diaries WHERE id = ? AND deleted_at IS NULL", diaryId)).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴한 댓글 작성자는 익명화하고 활성 작성자만 일괄 조회하면서 댓글 수와 커서 경계를 보존한다")
    void anonymizeDeletedAuthorsWithoutChangingCommentsOrPagination() throws Exception {
        UUID activeCommenter = createMember();
        UUID first = service.create(commenterId, feedId, "탈퇴 회원의 첫 댓글").id();
        UUID second = service.create(activeCommenter, feedId, "활성 회원의 첫 댓글").id();
        UUID third = service.create(commenterId, feedId, "탈퇴 회원의 두 번째 댓글").id();
        UUID fourth = service.create(activeCommenter, feedId, "활성 회원의 두 번째 댓글").id();
        List<UUID> commentIds = List.of(first, second, third, fourth);
        for (int index = 0; index < commentIds.size(); index++) {
            jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?",
                    Timestamp.from(FIXTURE_TIME.plusSeconds(index)), commentIds.get(index));
        }
        List<UUID> notificationIds = jdbc.queryForList(
                "SELECT id FROM notifications WHERE feed_id = ? ORDER BY id", UUID.class, feedId);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", commenterId);
        profiles.resetCallCount();

        String firstResponse = mockMvc.perform(bearer(get(commentsPath())).param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.items[0].content").value("탈퇴 회원의 첫 댓글"))
                .andExpect(jsonPath("$.items[0].author").isMap())
                .andExpect(jsonPath("$.items[0].author.id").value((Object) null))
                .andExpect(jsonPath("$.items[0].author.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.items[0].author.profileImageUrl").value((Object) null))
                .andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.items[1].id").value(second.toString()))
                .andExpect(jsonPath("$.items[1].author.id").value(activeCommenter.toString()))
                .andExpect(jsonPath("$.items[1].author.profileImageUrl")
                        .value("https://test.example.com/profiles/1.png"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String nextCursor = objectMapper.readTree(firstResponse).path("nextCursor").asText();
        assertThat(profiles.callCount()).isEqualTo(1);
        assertThat(profiles.lastRequestedIds()).containsExactly(activeCommenter);

        mockMvc.perform(get(commentsPath()).param("size", "2").param("cursor", nextCursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(third.toString()))
                .andExpect(jsonPath("$.items[0].author.id").value((Object) null))
                .andExpect(jsonPath("$.items[0].author.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.items[0].author.profileImageUrl").value((Object) null))
                .andExpect(jsonPath("$.items[1].id").value(fourth.toString()))
                .andExpect(jsonPath("$.items[1].author.id").value(activeCommenter.toString()))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
        assertThat(profiles.callCount()).isEqualTo(2);
        assertThat(profiles.lastRequestedIds()).containsExactly(activeCommenter);
        assertState(4, 4);
        assertThat(jdbc.queryForList("SELECT id FROM comments WHERE feed_id = ? AND deleted_at IS NULL "
                + "ORDER BY created_at, id", UUID.class, feedId)).containsExactlyElementsOf(commentIds);
        assertThat(jdbc.queryForList("SELECT id FROM notifications WHERE feed_id = ? ORDER BY id", UUID.class, feedId))
                .containsExactlyElementsOf(notificationIds);
    }

    @Test
    @DisplayName("페이지의 작성자가 모두 탈퇴했다면 프로필을 조회하지 않고 댓글 내용과 페이지를 유지한다")
    void returnAllDeletedAuthorsWithoutReadingProfiles() throws Exception {
        UUID first = service.create(commenterId, feedId, "보존되는 첫 댓글").id();
        UUID second = service.create(commenterId, feedId, "보존되는 두 번째 댓글").id();
        jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?", Timestamp.from(FIXTURE_TIME), first);
        jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?", Timestamp.from(FIXTURE_TIME.plusSeconds(1)), second);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", commenterId);
        profiles.reset();

        mockMvc.perform(bearer(get(commentsPath())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.items[0].content").value("보존되는 첫 댓글"))
                .andExpect(jsonPath("$.items[0].author.id").value((Object) null))
                .andExpect(jsonPath("$.items[0].author.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.items[0].author.profileImageUrl").value((Object) null))
                .andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.items[1].id").value(second.toString()))
                .andExpect(jsonPath("$.items[1].content").value("보존되는 두 번째 댓글"))
                .andExpect(jsonPath("$.items[1].author.id").value((Object) null))
                .andExpect(jsonPath("$.items[1].author.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.items[1].author.profileImageUrl").value((Object) null))
                .andExpect(jsonPath("$.items[1].isMine").value(false))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));

        assertThat(profiles.callCount()).isZero();
        assertThat(profiles.lastRequestedIds()).isEmpty();
        assertState(2, 2);
    }

    @Test
    @DisplayName("댓글 상태 조회 후 작성자가 탈퇴해도 같은 읽기 스냅샷의 프로필을 반환하고 다음 요청에서 익명화한다")
    void useConsistentSnapshotWhenAuthorIsDeletedDuringProfileRead() throws Exception {
        UUID activeCommenter = createMember();
        UUID first = service.create(commenterId, feedId, "조회와 탈퇴가 경쟁하는 댓글").id();
        UUID second = service.create(activeCommenter, feedId, "계속 활성인 회원의 댓글").id();
        jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?", Timestamp.from(FIXTURE_TIME), first);
        jdbc.update("UPDATE comments SET created_at = ? WHERE id = ?", Timestamp.from(FIXTURE_TIME.plusSeconds(1)), second);
        profiles.resetCallCount();
        profiles.beforeRead = () -> {
            assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).isEqualTo("repeatable read");
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<?> deletion = executor.submit(() -> transactions.executeWithoutResult(status ->
                        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", commenterId)));
                deletion.get(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("프로필 조회 중 다른 트랜잭션에서 회원 탈퇴를 완료하지 못했습니다.", exception);
            }
        };

        mockMvc.perform(bearer(get(commentsPath())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.items[0].author.id").value(commenterId.toString()))
                .andExpect(jsonPath("$.items[0].author.nickname").value("댓글통합테스트회원1"))
                .andExpect(jsonPath("$.items[0].author.profileImageUrl")
                        .value("https://test.example.com/profiles/1.png"))
                .andExpect(jsonPath("$.items[0].isMine").value(true))
                .andExpect(jsonPath("$.items[1].author.id").value(activeCommenter.toString()));
        assertThat(profiles.lastRequestedIds()).containsExactlyInAnyOrder(commenterId, activeCommenter);
        assertThat(intValue("SELECT count(*) FROM users WHERE id = ? AND deleted_at IS NOT NULL", commenterId))
                .isEqualTo(1);
        assertState(2, 2);

        mockMvc.perform(bearer(get(commentsPath())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.items[0].author.id").value((Object) null))
                .andExpect(jsonPath("$.items[0].author.nickname").value("탈퇴한 회원"))
                .andExpect(jsonPath("$.items[0].author.profileImageUrl").value((Object) null))
                .andExpect(jsonPath("$.items[0].isMine").value(false))
                .andExpect(jsonPath("$.items[1].id").value(second.toString()))
                .andExpect(jsonPath("$.items[1].author.id").value(activeCommenter.toString()));
        assertThat(profiles.callCount()).isEqualTo(2);
        assertThat(profiles.lastRequestedIds()).containsExactly(activeCommenter);
        assertState(2, 2);
    }

    @Test
    @DisplayName("댓글은 삽입 순서가 아닌 작성 시각과 UUID 오름차순으로 결정적으로 정렬된다")
    void sortOldestFirstWithUuidTieBreaker() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000011");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000012");
        UUID third = UUID.fromString("00000000-0000-0000-0000-000000000013");
        insertComment(third, commenterId, FIXTURE_TIME.plusSeconds(1), "나중 댓글");
        insertComment(second, commenterId, FIXTURE_TIME, "동일 시각 두 번째");
        insertComment(first, feedAuthorId, FIXTURE_TIME, "동일 시각 첫 번째");

        var page = service.list(feedId, null, null, 20);

        assertThat(page.items().stream().map(comment -> comment.id()).toList()).containsExactly(first, second, third);
        assertThat(page.items()).allMatch(comment -> !comment.isMine());
        assertThat(page.hasNext()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    @DisplayName("목록은 필요한 프로필을 한 번에 조회하고 현재 닉네임을 반환한다")
    void batchProfilesForReturnedPage() {
        UUID anotherCommenter = createMember();
        service.create(commenterId, feedId, "첫 댓글");
        service.create(commenterId, feedId, "같은 작성자의 두 번째");
        service.create(anotherCommenter, feedId, "다른 작성자");
        jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", "새닉네임", commenterId);
        profiles.resetCallCount();

        var page = service.list(feedId, commenterId, null, 20);

        assertThat(profiles.callCount()).isEqualTo(1);
        assertThat(profiles.lastRequestedIds()).containsExactlyInAnyOrder(commenterId, anotherCommenter);
        assertThat(page.items().getFirst().author().nickname()).isEqualTo("새닉네임");
        assertThat(page.items().getFirst().isMine()).isTrue();
        assertThat(page.items().getLast().isMine()).isFalse();
    }

    @Test
    @DisplayName("기본 페이지는 20개이며 size+1로 다음 페이지를 판단하고 마지막 커서는 null이다")
    void paginateDefaultTwentyAndFinalPageThroughHttp() throws Exception {
        for (int index = 0; index < 21; index++) {
            insertComment(UUID.randomUUID(), commenterId, FIXTURE_TIME.plusSeconds(index), "댓글 " + index);
        }
        mockMvc.perform(get(commentsPath()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.nextCursor").isNotEmpty());
        var first = service.list(feedId, null, null, 20);
        var last = service.list(feedId, null, first.nextCursor(), 20);
        assertThat(first.items()).hasSize(20);
        assertThat(last.items()).hasSize(1);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    @DisplayName("커서는 피드에 묶이고 잘못된 값·범위 밖 size는 실제 HTTP에서 400이다")
    void validateFeedBoundCursorAndPageSize() throws Exception {
        insertComment(UUID.randomUUID(), commenterId, FIXTURE_TIME, "첫 번째");
        insertComment(UUID.randomUUID(), commenterId, FIXTURE_TIME.plusSeconds(1), "두 번째");
        String cursor = service.list(feedId, null, null, 1).nextCursor();
        UUID anotherFeed = createFeed(createDiary(feedAuthorId));

        mockMvc.perform(get("/api/v1/feeds/" + anotherFeed + "/comments").param("cursor", cursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        mockMvc.perform(get(commentsPath()).param("cursor", "not-a-valid-cursor"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        for (String size : List.of("0", "51", "not-a-number")) {
            mockMvc.perform(get(commentsPath()).param("size", size))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mockMvc.perform(get(commentsPath()).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.hasNext").value(true));
        mockMvc.perform(get(commentsPath()).param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    @Test
    @DisplayName("페이지 경계 댓글을 삭제해도 커서의 정렬 값으로 다음 페이지를 이어 볼 수 있다")
    void continuePageAfterBoundaryCommentWasDeleted() {
        UUID boundary = UUID.randomUUID();
        UUID next = UUID.randomUUID();
        insertComment(boundary, commenterId, FIXTURE_TIME, "경계 댓글");
        insertComment(next, commenterId, FIXTURE_TIME.plusSeconds(1), "다음 댓글");
        String cursor = service.list(feedId, null, null, 1).nextCursor();
        service.delete(commenterId, boundary);

        var page = service.list(feedId, null, cursor, 1);

        assertThat(page.items().stream().map(comment -> comment.id()).toList()).containsExactly(next);
        assertThat(page.nextCursor()).isNull();
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    @DisplayName("댓글 알림은 실제 댓글 ID와 피드 작성자를 사용하고 자기 댓글 알림은 건너뛴다")
    void persistNotificationSourceRecipientAndSkipSelfComment() {
        var external = service.create(commenterId, feedId, "타인의 댓글");
        var own = service.create(feedAuthorId, feedId, "자기 댓글");

        assertState(2, 1);
        assertThat(uuidValue("SELECT source_action_id FROM notifications WHERE feed_id = ?", feedId))
                .isEqualTo(external.id());
        assertThat(uuidValue("SELECT recipient_user_id FROM notifications WHERE feed_id = ?", feedId))
                .isEqualTo(feedAuthorId);
        assertThat(uuidValue("SELECT actor_user_id FROM notifications WHERE feed_id = ?", feedId))
                .isEqualTo(commenterId);
        assertThat(jdbc.queryForObject("SELECT type FROM notifications WHERE feed_id = ?", String.class, feedId))
                .isEqualTo("COMMENT");
        service.delete(commenterId, external.id());
        assertState(1, 1);
        assertThat(service.list(feedId, null, null, 20).items().getFirst().id()).isEqualTo(own.id());
    }

    @Test
    @DisplayName("같은 본문을 새로 전송하면 별도 댓글이며 댓글 POST에는 멱등 재시도 계약을 추가하지 않는다")
    void allowSeparateCommentsWithSameContent() {
        UUID first = service.create(commenterId, feedId, "같은 댓글").id();
        UUID second = service.create(commenterId, feedId, "같은 댓글").id();

        assertThat(first).isNotEqualTo(second);
        assertState(2, 2);
    }

    @Test
    @DisplayName("동시 댓글 작성은 모든 댓글·개수·알림을 유실 없이 저장한다")
    void persistConcurrentCreatesWithoutLostCounts() throws Exception {
        List<Callable<UUID>> tasks = IntStream.range(0, WORKERS)
                .mapToObj(index -> (Callable<UUID>) () -> service.create(commenterId, feedId, "동시 댓글 " + index).id())
                .toList();

        List<UUID> ids = runConcurrently(tasks);

        assertThat(ids).hasSize(WORKERS).doesNotHaveDuplicates();
        assertState(WORKERS, WORKERS);
        assertThat(jdbc.queryForList("SELECT content FROM comments WHERE feed_id = ?", String.class, feedId))
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, WORKERS)
                        .mapToObj(index -> "동시 댓글 " + index).toList());
    }

    @Test
    @DisplayName("같은 댓글의 동시 삭제는 한 번만 성공하고 개수 감소도 한 번만 반영한다")
    void serializeConcurrentDeletesOfSameComment() throws Exception {
        UUID comment = service.create(commenterId, feedId, "삭제 경쟁 댓글").id();
        List<Callable<Throwable>> tasks = IntStream.range(0, WORKERS)
                .mapToObj(index -> (Callable<Throwable>) () -> catchThrowable(() -> service.delete(commenterId, comment)))
                .toList();

        List<Throwable> results = runConcurrently(tasks);

        assertThat(results.stream().filter(result -> result == null).count()).isEqualTo(1);
        assertThat(results.stream().filter(result -> result != null).toList()).hasSize(WORKERS - 1)
                .allMatch(error -> error instanceof CommentNotFoundException);
        assertState(0, 1);
    }

    @Test
    @DisplayName("동시 댓글 생성과 다른 댓글 삭제는 같은 피드의 개수를 직렬화한다")
    void serializeCreateAndDeleteForSameFeed() throws Exception {
        UUID oldComment = service.create(commenterId, feedId, "기존 댓글").id();

        List<Boolean> results = runConcurrently(List.of(
                () -> {
                    service.create(commenterId, feedId, "새 댓글");
                    return true;
                },
                () -> {
                    service.delete(commenterId, oldComment);
                    return true;
                }
        ));

        assertThat(results).containsExactly(true, true);
        assertState(1, 2);
        assertThat(service.list(feedId, null, null, 20).items().getFirst().content()).isEqualTo("새 댓글");
    }

    @Test
    @DisplayName("실제 피드 삭제 서비스가 먼저 잠그면 기다리던 댓글 작성은 삭제 커밋 후 실패한다")
    void rejectCreateWaitingForFeedDeletion() throws Exception {
        assertDeletionWins(false);
    }

    @Test
    @DisplayName("실제 일기 삭제 서비스가 일기→피드를 잠그면 기다리던 댓글 작성은 삭제 후 실패한다")
    void rejectCreateWaitingForDiaryAndFeedDeletion() throws Exception {
        assertDeletionWins(true);
    }

    @Test
    @DisplayName("댓글 생성의 피드 잠금은 커밋까지 유지해 실제 피드 삭제 서비스와 직렬화한다")
    void serializeFeedDeletionAfterCommentCreation() throws Exception {
        assertDeletionAfterCommentCreation(false);
    }

    @Test
    @DisplayName("댓글 생성이 선점하면 실제 일기 삭제 서비스는 피드 잠금을 기다리고 댓글 개수를 보존한다")
    void serializeDiaryDeletionAfterCommentCreation() throws Exception {
        assertDeletionAfterCommentCreation(true);
    }

    private void assertDeletionAfterCommentCreation(boolean deleteDiary) throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch releaseCreation = new CountDownLatch(1);
        AtomicInteger deletionPid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> creation = executor.submit(() -> transactions.executeWithoutResult(status -> {
                service.create(commenterId, feedId, "먼저 작성한 댓글");
                created.countDown();
                await(releaseCreation);
            }));
            try {
                assertThat(created.await(5, TimeUnit.SECONDS)).isTrue();
                Future<?> deletion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                    deletionPid.set(intValue("SELECT pg_backend_pid()"));
                    deleteThroughProductionService(deleteDiary);
                }));
                awaitBlockedBackend(deletionPid);
                releaseCreation.countDown();
                creation.get(10, TimeUnit.SECONDS);
                deletion.get(10, TimeUnit.SECONDS);
                assertProductionDeletion(deleteDiary);
                assertState(1, 1);
                assertThatThrownBy(() -> service.list(feedId, null, null, 20))
                        .isInstanceOf(FeedNotFoundException.class);
            } finally {
                releaseCreation.countDown();
            }
        }
    }

    @Test
    @DisplayName("알림 INSERT 실패는 댓글 INSERT와 개수 증가를 함께 롤백한다")
    void rollbackCommentAndCounterWhenNotificationInsertFails() {
        String name = "test_fail_comment_notice_" + feedId.toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS "
                + "'BEGIN RAISE EXCEPTION ''test notification failure'' USING ERRCODE = ''23514''; END'");
        try {
            jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON notifications FOR EACH ROW "
                    + "WHEN (NEW.feed_id = '" + feedId + "'::uuid) EXECUTE FUNCTION " + name + "()");
            assertThatThrownBy(() -> service.create(commenterId, feedId, "롤백할 댓글"))
                    .isInstanceOf(RuntimeException.class);
            assertState(0, 0);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON notifications");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
        service.create(commenterId, feedId, "복구 후 댓글");
        assertState(1, 1);
    }

    @Test
    @DisplayName("개수 증가 실패는 선행 댓글 INSERT와 알림을 모두 롤백한다")
    void rollbackCreateWhenCounterOverflows() {
        jdbc.update("UPDATE feeds SET comment_count = ? WHERE id = ?", Integer.MAX_VALUE, feedId);

        assertThatThrownBy(() -> service.create(commenterId, feedId, "증가 실패 댓글"))
                .isInstanceOf(RuntimeException.class);

        assertThat(activeCommentCount()).isZero();
        assertThat(intValue("SELECT comment_count FROM feeds WHERE id = ?", feedId)).isEqualTo(Integer.MAX_VALUE);
        assertThat(notificationCount()).isZero();
    }

    @Test
    @DisplayName("개수 감소 실패는 댓글의 삭제 변경도 롤백해 원래 댓글과 알림을 보존한다")
    void rollbackDeleteWhenCounterWouldUnderflow() {
        UUID comment = service.create(commenterId, feedId, "감소 실패 댓글").id();
        jdbc.update("UPDATE feeds SET comment_count = 0 WHERE id = ?", feedId);

        assertThatThrownBy(() -> service.delete(commenterId, comment)).isInstanceOf(RuntimeException.class);

        assertThat(activeCommentCount()).isEqualTo(1);
        assertThat(uuidValue("SELECT id FROM comments WHERE feed_id = ? AND deleted_at IS NULL", feedId))
                .isEqualTo(comment);
        assertThat(intValue("SELECT comment_count FROM feeds WHERE id = ?", feedId)).isZero();
        assertThat(notificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("작성자 프로필을 제공할 수 없으면 빈 값을 위조하지 않고 503과 쓰기 없음으로 응답한다")
    void rejectCreationAndListWhenProfileIsUnavailable() throws Exception {
        jdbc.update("UPDATE users SET nickname = NULL WHERE id = ?", commenterId);
        mockMvc.perform(withCsrf(bearer(post(commentsPath())))
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody("프로필 없는 댓글")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMENT_UNAVAILABLE"));
        assertState(0, 0);
        mockMvc.perform(get(commentsPath())).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        insertComment(UUID.randomUUID(), commenterId, FIXTURE_TIME, "기존 댓글");
        mockMvc.perform(get(commentsPath())).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMENT_UNAVAILABLE"));
    }

    @Test
    @DisplayName("INSERT RETURNING 응답은 PostgreSQL에 실제 저장된 생성 시각의 정밀도를 사용한다")
    void returnActualPersistedTimestamp() {
        var created = service.create(commenterId, feedId, "저장 시각 검사 댓글");
        Timestamp saved = jdbc.queryForObject("SELECT created_at FROM comments WHERE id = ?",
                Timestamp.class, created.id());

        assertThat(saved).isNotNull();
        assertThat(created.createdAt()).isEqualTo(saved.toInstant());
        assertThat(created.createdAt().getNano() % 1000).isZero();
    }

    @Test
    @DisplayName("댓글 저장·삭제 어댑터는 독립 트랜잭션을 열지 않고 호출자의 처리 단위를 요구한다")
    void requireTransactionForRepositoryMutations() {
        assertThatThrownBy(() -> comments.create(UUID.randomUUID(), feedId, commenterId, "외부 트랜잭션 없음", FIXTURE_TIME))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> comments.delete(UUID.randomUUID(), feedId, FIXTURE_TIME))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertState(0, 0);
    }

    private void assertDeletionWins(boolean deleteDiary) throws Exception {
        CountDownLatch deletionReady = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        AtomicInteger creationPid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> deletion = executor.submit(() -> transactions.executeWithoutResult(status -> {
                deleteThroughProductionService(deleteDiary);
                deletionReady.countDown();
                await(releaseDeletion);
            }));
            try {
                assertThat(deletionReady.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Throwable> creation = executor.submit(() -> catchThrowable(() ->
                        transactions.executeWithoutResult(status -> {
                            creationPid.set(intValue("SELECT pg_backend_pid()"));
                            service.create(commenterId, feedId, "삭제와 경쟁한 댓글");
                        })));
                awaitBlockedBackend(creationPid);
                releaseDeletion.countDown();
                deletion.get(10, TimeUnit.SECONDS);
                assertThat(creation.get(10, TimeUnit.SECONDS)).isInstanceOf(FeedNotFoundException.class);
                assertProductionDeletion(deleteDiary);
                assertState(0, 0);
            } finally {
                releaseDeletion.countDown();
            }
        }
    }

    private void deleteThroughProductionService(boolean deleteDiary) {
        if (deleteDiary) {
            diaryDeletion.delete(feedAuthorId, diaryId);
        } else {
            feedDeletion.delete(feedAuthorId, feedId);
        }
    }

    private void assertProductionDeletion(boolean diaryDeleted) {
        Timestamp feedDeletedAt = jdbc.queryForObject("SELECT deleted_at FROM feeds WHERE id = ?", Timestamp.class, feedId);
        Timestamp diaryDeletedAt = jdbc.queryForObject("SELECT deleted_at FROM diaries WHERE id = ?", Timestamp.class, diaryId);
        assertThat(feedDeletedAt).isNotNull();
        if (diaryDeleted) {
            assertThat(diaryDeletedAt).isEqualTo(feedDeletedAt);
        } else {
            assertThat(diaryDeletedAt).isNull();
        }
        verify(outbox).cancelUnsentByFeed(feedId);
    }

    private <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(tasks.size())) {
            List<Future<T>> futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                await(start);
                return task.call();
            })).toList();
            try {
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                List<T> results = new ArrayList<>();
                for (Future<T> future : futures) {
                    results.add(future.get(15, TimeUnit.SECONDS));
                }
                return results;
            } finally {
                start.countDown();
            }
        }
    }

    private void awaitBlockedBackend(AtomicInteger pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (pid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock'
                    )
                    """, Boolean.class, pid.get()))) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("실제 피드 잠금 대기를 확인하지 못했습니다.");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 트랜잭션 조정 신호를 기다리지 못했습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("동시 트랜잭션 대기가 중단됐습니다.", exception);
        }
    }

    private UUID createUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name, nickname, profile_image_code) VALUES (?, ?, ?, 1)",
                id, "댓글통합테스트회원", "댓글통합테스트회원" + fixtureUsers.size());
        fixtureUsers.add(id);
        return id;
    }

    private UUID createMember() {
        UUID id = createUser();
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, id.toString());
        return id;
    }

    private UUID createDiary(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-12', ?)",
                id, ownerId, "댓글 통합 테스트 일기");
        return id;
    }

    private UUID createFeed(UUID sourceDiaryId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                + "(SELECT id FROM categories WHERE name = '일상'))", id, sourceDiaryId);
        return id;
    }

    private void insertComment(UUID id, UUID author, Instant time, String text) {
        jdbc.update("INSERT INTO comments (id, feed_id, author_id, content, created_at) VALUES (?, ?, ?, ?, ?)",
                id, feedId, author, text, Timestamp.from(time));
        jdbc.update("UPDATE feeds SET comment_count = comment_count + 1 WHERE id = ?", feedId);
    }

    private void assertState(int comments, int notificationCount) {
        assertThat(activeCommentCount()).isEqualTo(comments);
        assertThat(intValue("SELECT comment_count FROM feeds WHERE id = ?", feedId)).isEqualTo(comments);
        assertThat(notificationCount()).isEqualTo(notificationCount);
        assertThat(intValue("SELECT like_count FROM feeds WHERE id = ?", feedId)).isZero();
    }

    private int activeCommentCount() {
        return intValue("SELECT count(*) FROM comments WHERE feed_id = ? AND deleted_at IS NULL", feedId);
    }

    private int notificationCount() {
        return intValue("SELECT count(*) FROM notifications WHERE feed_id = ?", feedId);
    }

    private int intValue(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        assertThat(value).isNotNull();
        return value;
    }

    private UUID uuidValue(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, UUID.class, arguments);
    }

    private String commentsPath() {
        return "/api/v1/feeds/" + feedId + "/comments";
    }

    private static String commentPath(UUID id) {
        return "/api/v1/comments/" + id;
    }

    private String commentBody(String text) {
        return objectMapper.writeValueAsString(Map.of("content", text));
    }

    private static MockHttpServletRequestBuilder bearer(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer member-token");
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    private static Jwt jwt(String token, UUID userId) {
        return Jwt.withTokenValue(token).header("alg", "HS256").subject(userId.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    static class ProfileReadProbe {

        private final AtomicInteger calls = new AtomicInteger();
        private volatile Set<UUID> lastRequested = Set.of();
        private volatile Runnable beforeRead;

        void recordRead(Set<UUID> ids) {
            calls.incrementAndGet();
            lastRequested = Set.copyOf(ids);
            Runnable callback = beforeRead;
            beforeRead = null;
            if (callback != null) {
                callback.run();
            }
        }

        int callCount() {
            return calls.get();
        }

        Set<UUID> lastRequestedIds() {
            return lastRequested;
        }

        void resetCallCount() {
            calls.set(0);
        }

        void reset() {
            calls.set(0);
            lastRequested = Set.of();
            beforeRead = null;
        }
    }
}

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "harudle.generation.adapters.enabled=false",
        "harudle.profile.image-base-url=https://test.example.com/profiles"
})
@AutoConfigureMockMvc
class CommentProfileIntegrationPersistenceTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PublicProfileReader profiles;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("실제 프로필 구현으로 탈퇴한 작성자의 댓글만 있는 공개 페이지는 익명 작성자와 원문을 반환한다")
    void returnDeletedAuthorsWithRealProfileReader() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID deletedCommenter = UUID.randomUUID();
        UUID diary = UUID.randomUUID();
        UUID feed = UUID.randomUUID();
        UUID comment = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name, profile_image_code) VALUES (?, ?, 1)", owner, "활성피드작성자");
        jdbc.update("INSERT INTO users (id, name, profile_image_code, deleted_at) "
                + "VALUES (?, ?, 1, CURRENT_TIMESTAMP)", deletedCommenter, "이름을숨길댓글작성자");
        try {
            jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-12', ?)",
                    diary, owner, "탈퇴 댓글 공개 조회 테스트 일기");
            jdbc.update("INSERT INTO feeds (id, diary_id, category_id, comment_count) VALUES (?, ?, "
                    + "(SELECT id FROM categories WHERE name = '일상'), 1)", feed, diary);
            jdbc.update("INSERT INTO comments (id, feed_id, author_id, content) VALUES (?, ?, ?, ?)",
                    comment, feed, deletedCommenter, "작성자가 탈퇴해도 보존되는 댓글");

            assertThat(profiles).isInstanceOf(DefaultPublicProfileReader.class);
            mockMvc.perform(get("/api/v1/feeds/" + feed + "/comments"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value(comment.toString()))
                    .andExpect(jsonPath("$.items[0].content").value("작성자가 탈퇴해도 보존되는 댓글"))
                    .andExpect(jsonPath("$.items[0].author").isMap())
                    .andExpect(jsonPath("$.items[0].author.id").value((Object) null))
                    .andExpect(jsonPath("$.items[0].author.nickname").value("탈퇴한 회원"))
                    .andExpect(jsonPath("$.items[0].author.profileImageUrl").value((Object) null))
                    .andExpect(jsonPath("$.items[0].isMine").value(false))
                    .andExpect(jsonPath("$.hasNext").value(false))
                    .andExpect(jsonPath("$.nextCursor").value((Object) null));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE feed_id = ? AND deleted_at IS NULL",
                    Integer.class, feed)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT comment_count FROM feeds WHERE id = ?", Integer.class, feed))
                    .isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", owner);
            jdbc.update("DELETE FROM users WHERE id = ?", deletedCommenter);
        }
    }

    @Test
    @DisplayName("실제 프로필 빈은 미할당 회원의 작성을 503으로 거절하고 할당 후 정상 프로필로 댓글을 저장한다")
    void createCommentOnlyAfterPublicProfileIsAssigned() throws Exception {
        UUID user = UUID.randomUUID();
        UUID diary = UUID.randomUUID();
        UUID feed = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name, profile_image_code) VALUES (?, ?, 1)", user, "프로필미연동회원");
        try {
            jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                    user, user.toString());
            jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-12', ?)",
                    diary, user, "프로필 미연동 통합 테스트 일기");
            jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                    + "(SELECT id FROM categories WHERE name = '일상'))", feed, diary);
            when(jwtDecoder.decode("profile-unavailable-token")).thenReturn(
                    Jwt.withTokenValue("profile-unavailable-token").header("alg", "HS256")
                            .subject(user.toString()).issuedAt(Instant.now())
                            .expiresAt(Instant.now().plusSeconds(3600)).build());

            assertThat(profiles).isInstanceOf(DefaultPublicProfileReader.class);
            assertThat(profiles.readAll(Set.of(user))).isEmpty();
            String path = "/api/v1/feeds/" + feed + "/comments";
            mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.hasNext").value(false));
            mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer profile-unavailable-token")
                            .cookie(new Cookie("XSRF-TOKEN", "csrf-value")).header("X-XSRF-TOKEN", "csrf-value")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"프로필 없는 댓글\"}"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("COMMENT_UNAVAILABLE"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE feed_id = ?", Integer.class, feed))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT comment_count FROM feeds WHERE id = ?", Integer.class, feed))
                    .isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE feed_id = ?", Integer.class, feed))
                    .isZero();
            jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", "프로필연동회원", user);
            assertThat(profiles.readAll(Set.of(user))).containsKey(user);
            mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, "Bearer profile-unavailable-token")
                            .cookie(new Cookie("XSRF-TOKEN", "csrf-value")).header("X-XSRF-TOKEN", "csrf-value")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"프로필 연동 댓글\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.author.nickname").value("프로필연동회원"))
                    .andExpect(jsonPath("$.author.profileImageUrl").value("https://test.example.com/profiles/1.png"));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE feed_id = ?", Integer.class, feed))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT comment_count FROM feeds WHERE id = ?", Integer.class, feed))
                    .isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
    }
}
