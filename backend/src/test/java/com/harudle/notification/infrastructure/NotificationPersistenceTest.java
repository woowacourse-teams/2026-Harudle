package com.harudle.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.comment.query.CommentCursor;
import com.harudle.comment.service.CommentCursorCodec;
import com.harudle.comment.service.CommentService;
import com.harudle.feed.service.FeedLikeService;
import com.harudle.notification.repository.NotificationRepository;
import com.harudle.notification.service.NotificationService;
import com.harudle.notification.service.exception.NotificationNotFoundException;
import com.harudle.profile.service.port.PublicProfileReader;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "harudle.generation.adapters.enabled=false")
@AutoConfigureMockMvc
@Import(NotificationPersistenceTest.ProfileTestConfiguration.class)
class NotificationPersistenceTest {

    private static final Instant FIXTURE_TIME = Instant.parse("2026-10-12T03:00:00Z");
    private static final String CSRF_TOKEN = "notification-cookie-and-header-value";
    private static final int WORKERS = 8;

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private NotificationService service;
    @Autowired
    private NotificationRepository repository;
    @Autowired
    private FeedLikeService likes;
    @Autowired
    private CommentService comments;
    @Autowired
    private CommentCursorCodec commentCursorCodec;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseTestProfiles profiles;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private final List<UUID> fixtureUsers = new ArrayList<>();
    private UUID recipientId;
    private UUID actorId;
    private UUID otherRecipientId;
    private UUID diaryId;
    private UUID feedId;

    @BeforeEach
    void setUp() {
        profiles.reset();
        recipientId = createMember();
        actorId = createMember();
        otherRecipientId = createMember();
        diaryId = createDiary(recipientId);
        feedId = createFeed(diaryId);
        when(jwtDecoder.decode("recipient-token")).thenReturn(jwt("recipient-token", recipientId));
        when(jwtDecoder.decode("other-token")).thenReturn(jwt("other-token", otherRecipientId));
    }

    @AfterEach
    void tearDown() {
        profiles.reset();
        for (UUID user : fixtureUsers) {
            jdbc.update("DELETE FROM users WHERE id = ?", user);
        }
        fixtureUsers.clear();
    }

    @Test
    @DisplayName("실제 좋아요·댓글 저장부터 본인 HTTP 목록과 해당 항목 읽음까지 전체 경로를 연결한다")
    void listAndReadActualLikeAndCommentNotifications() throws Exception {
        likes.like(actorId, feedId);
        UUID comment = comments.create(actorId, feedId, "알림으로 확인할 댓글").id();
        likes.like(recipientId, feedId);
        comments.create(recipientId, feedId, "자기 댓글에는 알림 없음");

        mockMvc.perform(recipient(get(listPath())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.unreadCount").value(2))
                .andExpect(jsonPath("$.items[0].type").value("COMMENT"))
                .andExpect(jsonPath("$.items[0].feedId").value(feedId.toString()))
                .andExpect(jsonPath("$.items[0].actor.id").value(actorId.toString()))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.items[1].type").value("LIKE"));
        UUID notice = uuidValue("SELECT id FROM notifications WHERE source_action_id = ?", comment);
        mockMvc.perform(withCsrf(recipient(patch(readPath(notice))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isNoContent()).andExpect(content().string(""));

        var page = service.list(recipientId, null, 20);
        assertThat(page.unreadCount()).isEqualTo(1);
        assertThat(page.items().getFirst().read()).isTrue();
        assertThat(page.items().getLast().read()).isFalse();
        assertThat(intValue("SELECT count(*) FROM notifications WHERE feed_id = ?", feedId)).isEqualTo(2);
    }

    @Test
    @DisplayName("목록은 본인 알림만 반환하고 전체 미읽음 수는 페이지 길이와 다르다")
    void scopeRecipientAndCountAllUnreadAcrossPages() throws Exception {
        UUID newest = insertNotification(recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME.plusSeconds(2));
        insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME.plusSeconds(1));
        insertNotification(recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME);
        insertNotification(otherRecipientId, actorId, feedId, "LIKE", FIXTURE_TIME.plusSeconds(3));

        mockMvc.perform(recipient(get(listPath()).param("size", "1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(newest.toString()))
                .andExpect(jsonPath("$.unreadCount").value(3))
                .andExpect(jsonPath("$.hasNext").value(true)).andExpect(jsonPath("$.nextCursor").isNotEmpty());
        mockMvc.perform(get(listPath()).header(HttpHeaders.AUTHORIZATION, "Bearer other-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.unreadCount").value(1));
        assertThat(intValue("SELECT count(*) FROM notifications WHERE recipient_user_id = ? AND read_at IS NOT NULL",
                recipientId)).isZero();
    }

    @Test
    @DisplayName("목록은 생성시각과 UUID 내림차순이며 같은 시각 경계에서도 빠짐없이 이어진다")
    void sortNewestFirstWithUuidTieBreaker() {
        UUID older = UUID.fromString("00000000-0000-0000-0000-000000000021");
        UUID low = UUID.fromString("00000000-0000-0000-0000-000000000022");
        UUID high = UUID.fromString("00000000-0000-0000-0000-000000000023");
        insertNotification(high, recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        insertNotification(older, recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME.minusSeconds(1));
        insertNotification(low, recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME);

        var first = service.list(recipientId, null, 1);
        var rest = service.list(recipientId, first.nextCursor(), 2);

        assertThat(first.items().getFirst().id()).isEqualTo(high);
        assertThat(rest.items().stream().map(item -> item.id()).toList()).containsExactly(low, older);
        assertThat(rest.hasNext()).isFalse();
        assertThat(rest.nextCursor()).isNull();
        assertThat(rest.unreadCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("기본 20개 목록은 size+1로 다음 페이지를 확인하고 마지막 커서는 null이다")
    void useDefaultSizeAndCorrectFinalCursor() throws Exception {
        for (int index = 0; index < 21; index++) {
            insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME.plusSeconds(index));
        }
        mockMvc.perform(recipient(get(listPath())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(20)))
                .andExpect(jsonPath("$.unreadCount").value(21)).andExpect(jsonPath("$.hasNext").value(true));
        var first = service.list(recipientId, null, 20);
        var last = service.list(recipientId, first.nextCursor(), 20);
        assertThat(last.items()).hasSize(1);
        assertThat(last.nextCursor()).isNull();
        assertThat(last.hasNext()).isFalse();
        assertThat(last.unreadCount()).isEqualTo(21);
    }

    @Test
    @DisplayName("다른 계정의 알림 커서와 댓글 커서 및 잘못된 커서는 400으로 거절한다")
    void rejectWrongRecipientAndWrongKindCursors() throws Exception {
        insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        insertNotification(recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME.plusSeconds(1));
        String notificationCursor = service.list(recipientId, null, 1).nextCursor();
        String commentCursor = commentCursorCodec.encode(new CommentCursor(feedId, FIXTURE_TIME, UUID.randomUUID()));

        mockMvc.perform(get(listPath()).header(HttpHeaders.AUTHORIZATION, "Bearer other-token")
                        .param("cursor", notificationCursor))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        for (String cursor : List.of(commentCursor, "not-a-valid-cursor", "")) {
            mockMvc.perform(recipient(get(listPath()).param("cursor", cursor)))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "not-a-number"})
    @DisplayName("범위를 벗어나거나 정수가 아닌 페이지 크기는 검증 오류다")
    void rejectInvalidPageSize(String size) throws Exception {
        mockMvc.perform(recipient(get(listPath()).param("size", size)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("커서 경계 알림의 피드가 삭제돼도 저장한 정렬 값으로 다음 알림을 조회한다")
    void continueAfterBoundaryFeedWasDeleted() {
        UUID secondFeed = createFeed(createDiary(recipientId));
        UUID boundary = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME.plusSeconds(1));
        UUID next = insertNotification(recipientId, actorId, secondFeed, "COMMENT", FIXTURE_TIME);
        var first = service.list(recipientId, null, 1);
        assertThat(first.items().getFirst().id()).isEqualTo(boundary);
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feedId);

        var rest = service.list(recipientId, first.nextCursor(), 1);

        assertThat(rest.items().getFirst().id()).isEqualTo(next);
        assertThat(rest.hasNext()).isFalse();
        assertThat(rest.nextCursor()).isNull();
        assertThat(rest.unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("삭제 피드와 삭제 원본의 알림은 목록과 전체 미읽음 수에서 똑같이 제외한다")
    void excludeDeletedFeedAndDiaryFromItemsAndUnreadCount() {
        UUID deletedFeed = createFeed(createDiary(recipientId));
        UUID deletedDiary = createDiary(recipientId);
        UUID diaryDeletedFeed = createFeed(deletedDiary);
        UUID visible = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        insertNotification(recipientId, actorId, deletedFeed, "COMMENT", FIXTURE_TIME.plusSeconds(1));
        insertNotification(recipientId, actorId, diaryDeletedFeed, "LIKE", FIXTURE_TIME.plusSeconds(2));
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedFeed);
        jdbc.update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deletedDiary);

        var page = service.list(recipientId, null, 20);

        assertThat(page.items().stream().map(item -> item.id()).toList()).containsExactly(visible);
        assertThat(page.unreadCount()).isEqualTo(1);
        assertThat(intValue("SELECT count(*) FROM notifications WHERE recipient_user_id = ?", recipientId)).isEqualTo(3);
    }

    @Test
    @DisplayName("좋아요 취소로 행동 행을 물리 삭제해도 인앱 알림 이력과 원인 행동 ID를 보존한다")
    void preserveHistoryAfterUnlike() {
        likes.like(actorId, feedId);
        UUID sourceAction = uuidValue("SELECT id FROM feed_likes WHERE feed_id = ? AND user_id = ?", feedId, actorId);
        likes.unlike(actorId, feedId);

        var page = service.list(recipientId, null, 20);

        assertThat(intValue("SELECT count(*) FROM feed_likes WHERE id = ?", sourceAction)).isZero();
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().type().name()).isEqualTo("LIKE");
        assertThat(page.unreadCount()).isEqualTo(1);
        assertThat(uuidValue("SELECT source_action_id FROM notifications WHERE feed_id = ?", feedId))
                .isEqualTo(sourceAction);
    }

    @Test
    @DisplayName("댓글 삭제와 이후 물리 정리에도 원인 댓글 ID와 알림 이력을 보존한다")
    void preserveHistoryAfterCommentDeletionAndCleanup() {
        UUID comment = comments.create(actorId, feedId, "삭제할 댓글").id();
        comments.delete(actorId, comment);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM comments WHERE id = ?", Timestamp.class, comment))
                .isNotNull();
        assertThat(service.list(recipientId, null, 20).unreadCount()).isEqualTo(1);
        jdbc.update("DELETE FROM comments WHERE id = ?", comment);

        var page = service.list(recipientId, null, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().type().name()).isEqualTo("COMMENT");
        assertThat(page.unreadCount()).isEqualTo(1);
        assertThat(uuidValue("SELECT source_action_id FROM notifications WHERE feed_id = ?", feedId)).isEqualTo(comment);
    }

    @Test
    @DisplayName("반복 읽음은 최초 DB 시각을 유지하고 이후 생긴 다른 알림은 읽지 않는다")
    void preserveFirstReadTimeAndDoNotReadNewNotifications() {
        UUID first = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        service.markRead(recipientId, first);
        Instant originalReadAt = readAt(first);
        UUID second = insertNotification(recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME.plusSeconds(1));

        service.markRead(recipientId, first);

        assertThat(readAt(first)).isEqualTo(originalReadAt);
        assertThat(originalReadAt.getNano() % 1000).isZero();
        assertThat(readAt(second)).isNull();
        assertThat(service.list(recipientId, null, 20).unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("동시 읽음 요청은 모두 성공하되 한 번 기록한 read_at을 더 늦은 값으로 덮어쓰지 않는다")
    void keepFirstReadTimeUnderConcurrentReadRequests() throws Exception {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        List<Callable<Boolean>> tasks = IntStream.range(0, WORKERS)
                .mapToObj(index -> (Callable<Boolean>) () -> {
                    service.markRead(recipientId, notice);
                    return true;
                }).toList();

        assertThat(runConcurrently(tasks)).containsOnly(true).hasSize(WORKERS);
        Instant firstRead = readAt(notice);
        assertThat(firstRead).isNotNull();
        service.markRead(recipientId, notice);
        assertThat(readAt(notice)).isEqualTo(firstRead);
        assertThat(service.list(recipientId, null, 20).unreadCount()).isZero();
    }

    @Test
    @DisplayName("이미 기록된 최초 읽음 시각은 실제 HTTP 반복 요청에서도 유지된다")
    void keepExistingReadTimeThroughHttp() throws Exception {
        UUID notice = insertNotification(recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME);
        jdbc.update("UPDATE notifications SET read_at = ? WHERE id = ?", Timestamp.from(FIXTURE_TIME), notice);

        for (int repeat = 0; repeat < 2; repeat++) {
            mockMvc.perform(withCsrf(recipient(patch(readPath(notice))))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                    .andExpect(status().isNoContent()).andExpect(content().string(""));
        }
        assertThat(readAt(notice)).isEqualTo(FIXTURE_TIME);
        assertThat(service.list(recipientId, null, 20).unreadCount()).isZero();
    }

    @Test
    @DisplayName("본인 알림만 읽을 수 있으며 타인 403·없는 대상 404·삭제된 부모 대상 404를 구분한다")
    void validateOwnershipAndVisibleParentsForRead() throws Exception {
        UUID own = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        UUID foreign = insertNotification(otherRecipientId, actorId, feedId, "COMMENT", FIXTURE_TIME);
        mockMvc.perform(withCsrf(recipient(patch(readPath(foreign))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(withCsrf(recipient(patch(readPath(UUID.randomUUID()))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        jdbc.update("UPDATE feeds SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", feedId);
        mockMvc.perform(withCsrf(recipient(patch(readPath(own))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        assertThat(readAt(own)).isNull();
        assertThat(readAt(foreign)).isNull();

        UUID anotherDiary = createDiary(recipientId);
        UUID anotherFeed = createFeed(anotherDiary);
        UUID diaryNotice = insertNotification(recipientId, actorId, anotherFeed, "LIKE", FIXTURE_TIME);
        jdbc.update("UPDATE diaries SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", anotherDiary);
        mockMvc.perform(withCsrf(recipient(patch(readPath(diaryNotice))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
        assertThat(readAt(diaryNotice)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"read\":null}", "{\"read\":false}", "{\"read\":\"not-a-boolean\"}"})
    @DisplayName("읽음 요청은 read=true만 받으며 잘못된 본문은 상태를 바꾸지 않는다")
    void rejectInvalidReadBody(String body) throws Exception {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        mockMvc.perform(withCsrf(recipient(patch(readPath(notice))))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(readAt(notice)).isNull();
    }

    @Test
    @DisplayName("잘못된 알림 UUID는 500이나 데이터 변경 없이 검증 오류로 반환한다")
    void rejectMalformedNotificationId() throws Exception {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        mockMvc.perform(withCsrf(recipient(patch(listPath() + "/not-a-uuid")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
        assertThat(readAt(notice)).isNull();
    }

    @Test
    @DisplayName("실제 GET은 인증이 필요하고 PATCH는 인증과 CSRF가 모두 필요하다")
    void enforceAuthenticationAndCsrfThroughFullHttpStack() throws Exception {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        mockMvc.perform(get(listPath())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(patch(readPath(notice)).contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(recipient(patch(readPath(notice)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("INVALID_CSRF_TOKEN"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
        assertThat(readAt(notice)).isNull();
    }

    @Test
    @DisplayName("유효 JWT라도 OAuth 회원이 아니거나 탈퇴한 회원이면 알림 조회와 읽음을 허용하지 않는다")
    void rejectGuestAndInactiveUserEvenWithValidJwt() throws Exception {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        UUID guest = createUser();
        when(jwtDecoder.decode("guest-token")).thenReturn(jwt("guest-token", guest));
        mockMvc.perform(get(listPath()).header(HttpHeaders.AUTHORIZATION, "Bearer guest-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(withCsrf(patch(readPath(notice)).header(HttpHeaders.AUTHORIZATION, "Bearer guest-token"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", recipientId);
        mockMvc.perform(recipient(get(listPath()))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        assertThat(readAt(notice)).isNull();
    }

    @Test
    @DisplayName("활성 행위자 프로필은 반환 페이지의 회원들만 한 번에 조회하고 현재 닉네임을 표시한다")
    void loadProfilesOnceForReturnedPageOnly() {
        UUID anotherActor = createMember();
        UUID unseenActor = createMember();
        insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME.plusSeconds(2));
        insertNotification(recipientId, anotherActor, feedId, "COMMENT", FIXTURE_TIME.plusSeconds(1));
        insertNotification(recipientId, unseenActor, feedId, "LIKE", FIXTURE_TIME);
        jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", "바뀐이름", actorId);
        profiles.resetCallCount();

        var page = service.list(recipientId, null, 2);

        assertThat(profiles.callCount()).isEqualTo(1);
        assertThat(profiles.lastRequestedIds()).containsExactlyInAnyOrder(actorId, anotherActor);
        assertThat(page.items().getFirst().actor().nickname()).isEqualTo("바뀐이름");
        assertThat(page.items().getFirst().message()).contains("바뀐이름");
        assertThat(page.hasNext()).isTrue();
    }

    @Test
    @DisplayName("행위자가 물리 삭제되면 FK가 null로 바뀌며 프로필 조회 없이 탈퇴한 회원으로 이력을 보여준다")
    void retainNotificationAfterActorWasHardDeleted() throws Exception {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        jdbc.update("DELETE FROM users WHERE id = ?", actorId);
        profiles.resetCallCount();

        mockMvc.perform(recipient(get(listPath())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(notice.toString()))
                .andExpect(jsonPath("$.items[0].actor").value((Object) null))
                .andExpect(jsonPath("$.items[0].message", containsString("탈퇴한 회원")))
                .andExpect(jsonPath("$.unreadCount").value(1));
        assertThat(profiles.callCount()).isZero();
    }

    @Test
    @DisplayName("행위자가 논리 탈퇴하면 DB actor ID는 보존하되 공개 응답에는 null과 탈퇴 안내를 반환한다")
    void retainNotificationAfterActorWasSoftDeleted() {
        UUID notice = insertNotification(recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME);
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", actorId);
        profiles.resetCallCount();

        var page = service.list(recipientId, null, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().actor()).isNull();
        assertThat(page.items().getFirst().message()).contains("탈퇴한 회원");
        assertThat(uuidValue("SELECT actor_user_id FROM notifications WHERE id = ?", notice)).isEqualTo(actorId);
        assertThat(profiles.callCount()).isZero();
        assertThat(page.unreadCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("활성 행위자 프로필 누락은 503이지만 빈 목록과 읽음 처리는 프로필에 의존하지 않는다")
    void failUnavailableProfilesOnlyWhenListNeedsThem() throws Exception {
        profiles.omitAllProfiles = true;
        mockMvc.perform(recipient(get(listPath()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0))).andExpect(jsonPath("$.unreadCount").value(0));
        assertThat(profiles.callCount()).isZero();
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        mockMvc.perform(recipient(get(listPath()))).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_UNAVAILABLE"));
        mockMvc.perform(withCsrf(recipient(patch(readPath(notice))))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                .andExpect(status().isNoContent());
        assertThat(readAt(notice)).isNotNull();
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", actorId);
        mockMvc.perform(recipient(get(listPath()))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actor").value((Object) null))
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    @DisplayName("GET은 실제 REPEATABLE READ에서 목록·전체 수·프로필을 같은 스냅샷으로 조립한다")
    void useOneRepeatableReadSnapshotAcrossConcurrentMutations() throws Exception {
        UUID first = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME.plusSeconds(1));
        UUID second = insertNotification(recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME);
        CountDownLatch pageRead = new CountDownLatch(1);
        CountDownLatch continueRead = new CountDownLatch(1);
        profiles.beforeQuery = () -> {
            pageRead.countDown();
            await(continueRead);
        };
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var reading = executor.submit(() -> service.list(recipientId, null, 20));
            try {
                assertThat(pageRead.await(5, TimeUnit.SECONDS)).isTrue();
                service.markRead(recipientId, first);
                service.markRead(recipientId, second);
                insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME.plusSeconds(2));
                jdbc.update("UPDATE users SET nickname = ? WHERE id = ?", "변경후이름", actorId);
                continueRead.countDown();

                var snapshot = reading.get(10, TimeUnit.SECONDS);

                assertThat(profiles.lastIsolation()).isEqualTo("repeatable read");
                assertThat(snapshot.items()).hasSize(2).allMatch(item -> !item.read());
                assertThat(snapshot.unreadCount()).isEqualTo(2);
                assertThat(snapshot.items().getFirst().actor().nickname()).isEqualTo("알림통합테스트회원");
                profiles.beforeQuery = null;
                assertThat(service.list(recipientId, null, 20).unreadCount()).isEqualTo(1);
            } finally {
                continueRead.countDown();
                profiles.beforeQuery = null;
            }
        }
    }

    @Test
    @DisplayName("읽음 UPDATE 실패는 read_at을 롤백하며 다시 조회한 미읽음 개수도 유지된다")
    void rollbackReadWhenDatabaseUpdateFails() {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        String name = "test_fail_notice_read_" + notice.toString().replace("-", "");
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS "
                + "'BEGIN RAISE EXCEPTION ''test read failure'' USING ERRCODE = ''23514''; END'");
        try {
            jdbc.execute("CREATE TRIGGER " + name + " AFTER UPDATE OF read_at ON notifications FOR EACH ROW "
                    + "WHEN (NEW.id = '" + notice + "'::uuid) EXECUTE FUNCTION " + name + "()");
            assertThatThrownBy(() -> service.markRead(recipientId, notice)).isInstanceOf(RuntimeException.class);
            assertThat(readAt(notice)).isNull();
            assertThat(service.list(recipientId, null, 20).unreadCount()).isEqualTo(1);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS " + name + " ON notifications");
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    @Test
    @DisplayName("읽음 어댑터는 외부 트랜잭션을 요구하고 다른 수신자 ID로는 UPDATE하지 않는다")
    void requireTransactionAndRecipientForRepositoryUpdate() {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        assertThatThrownBy(() -> repository.markRead(notice, recipientId, FIXTURE_TIME))
                .isInstanceOf(IllegalTransactionStateException.class);
        transactions.executeWithoutResult(status ->
                assertThat(repository.markRead(notice, otherRecipientId, FIXTURE_TIME)).isFalse());
        assertThat(readAt(notice)).isNull();
    }

    @Test
    @DisplayName("알림 삭제와 경합한 읽음은 기다린 뒤 없는 대상으로 실패하고 다른 알림을 변경하지 않는다")
    void rejectReadAfterConcurrentNotificationRemoval() throws Exception {
        UUID notice = insertNotification(recipientId, actorId, feedId, "LIKE", FIXTURE_TIME);
        UUID remaining = insertNotification(recipientId, actorId, feedId, "COMMENT", FIXTURE_TIME.minusSeconds(1));
        CountDownLatch removed = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        AtomicInteger readingPid = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> removal = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM notifications WHERE id = ?", notice);
                removed.countDown();
                await(releaseRemoval);
            }));
            try {
                assertThat(removed.await(5, TimeUnit.SECONDS)).isTrue();
                Future<Throwable> reading = executor.submit(() -> catchThrowable(() ->
                        transactions.executeWithoutResult(status -> {
                            readingPid.set(intValue("SELECT pg_backend_pid()"));
                            service.markRead(recipientId, notice);
                        })));
                awaitBlockedBackend(readingPid);
                releaseRemoval.countDown();
                removal.get(10, TimeUnit.SECONDS);
                assertThat(reading.get(10, TimeUnit.SECONDS)).isInstanceOf(NotificationNotFoundException.class);
                assertThat(readAt(remaining)).isNull();
                assertThat(service.list(recipientId, null, 20).unreadCount()).isEqualTo(1);
            } finally {
                releaseRemoval.countDown();
            }
        }
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
        throw new AssertionError("실제 알림 행 잠금 대기를 확인하지 못했습니다.");
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
        jdbc.update("INSERT INTO users (id, name, profile_image_code) VALUES (?, ?, 1)", id, "알림통합테스트회원");
        fixtureUsers.add(id);
        return id;
    }

    private UUID createMember() {
        UUID id = createUser();
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, id.toString());
        return id;
    }

    private UUID createDiary(UUID owner) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-12', ?)",
                id, owner, "알림 통합 테스트 일기");
        return id;
    }

    private UUID createFeed(UUID diary) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                + "(SELECT id FROM categories WHERE name = '일상'))", id, diary);
        return id;
    }

    private UUID insertNotification(UUID recipient, UUID actor, UUID feed, String type, Instant time) {
        UUID id = UUID.randomUUID();
        insertNotification(id, recipient, actor, feed, type, time);
        return id;
    }

    private void insertNotification(UUID id, UUID recipient, UUID actor, UUID feed, String type, Instant time) {
        jdbc.update("""
                INSERT INTO notifications
                    (id, recipient_user_id, actor_user_id, feed_id, type, source_action_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, recipient, actor, feed, type, UUID.randomUUID(), Timestamp.from(time));
    }

    private Instant readAt(UUID id) {
        Timestamp value = jdbc.queryForObject("SELECT read_at FROM notifications WHERE id = ?", Timestamp.class, id);
        return value == null ? null : value.toInstant();
    }

    private int intValue(String sql, Object... arguments) {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        assertThat(value).isNotNull();
        return value;
    }

    private UUID uuidValue(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, UUID.class, arguments);
    }

    private static String listPath() {
        return "/api/v1/notifications";
    }

    private static String readPath(UUID id) {
        return listPath() + "/" + id;
    }

    private static MockHttpServletRequestBuilder recipient(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer recipient-token");
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.cookie(new Cookie("XSRF-TOKEN", CSRF_TOKEN)).header("X-XSRF-TOKEN", CSRF_TOKEN);
    }

    private static Jwt jwt(String token, UUID user) {
        return Jwt.withTokenValue(token).header("alg", "HS256").subject(user.toString())
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProfileTestConfiguration {

        @Bean
        DatabaseTestProfiles testOnlyPublicProfileReader(JdbcTemplate jdbc) {
            return new DatabaseTestProfiles(jdbc);
        }
    }

    /** This database-backed profile implementation is restricted to the test context. */
    static class DatabaseTestProfiles implements PublicProfileReader {

        private final JdbcTemplate jdbc;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile Set<UUID> lastRequested = Set.of();
        private volatile String isolation;
        private volatile boolean omitAllProfiles;
        private volatile Runnable beforeQuery;

        DatabaseTestProfiles(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @Override
        public Map<UUID, Profile> readAll(Set<UUID> ids) {
            calls.incrementAndGet();
            lastRequested = Set.copyOf(ids);
            isolation = jdbc.queryForObject("SHOW transaction_isolation", String.class);
            Runnable hook = beforeQuery;
            if (hook != null) {
                hook.run();
            }
            if (omitAllProfiles || ids.isEmpty()) {
                return Map.of();
            }
            String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
            Map<UUID, Profile> result = new HashMap<>();
            jdbc.query("SELECT id, COALESCE(nickname, name), profile_image_code FROM users "
                            + "WHERE deleted_at IS NULL AND id IN (" + placeholders + ")",
                    row -> {
                        UUID id = row.getObject(1, UUID.class);
                        result.put(id, new Profile(id, row.getString(2),
                                URI.create("https://test.example.com/profiles/" + row.getInt(3) + ".png")));
                    }, ids.toArray());
            return Map.copyOf(result);
        }

        int callCount() {
            return calls.get();
        }

        Set<UUID> lastRequestedIds() {
            return lastRequested;
        }

        String lastIsolation() {
            return isolation;
        }

        void resetCallCount() {
            calls.set(0);
        }

        void reset() {
            calls.set(0);
            lastRequested = Set.of();
            isolation = null;
            omitAllProfiles = false;
            beforeQuery = null;
        }
    }
}

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "harudle.generation.adapters.enabled=false")
@AutoConfigureMockMvc
class NotificationWithoutProfilePersistenceTest {

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectProvider<PublicProfileReader> profiles;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("프로필 구현 없이도 앱이 시작하고 빈 목록·탈퇴 행위자·읽음은 성공하며 활성 행위자 목록만 503이다")
    void bootAndReadWithoutProfileImplementation() throws Exception {
        UUID recipient = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        UUID diary = UUID.randomUUID();
        UUID feed = UUID.randomUUID();
        UUID notice = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, name) VALUES (?, ?), (?, ?)", recipient, "수신회원", actor, "행위회원");
        try {
            jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                    recipient, recipient.toString());
            jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, DATE '2026-10-12', ?)",
                    diary, recipient, "프로필 미연동 알림 테스트 일기");
            jdbc.update("INSERT INTO feeds (id, diary_id, category_id) VALUES (?, ?, "
                    + "(SELECT id FROM categories WHERE name = '일상'))", feed, diary);
            when(jwtDecoder.decode("no-profile-token")).thenReturn(Jwt.withTokenValue("no-profile-token")
                    .header("alg", "HS256").subject(recipient.toString()).issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(3600)).build());
            assertThat(profiles.getIfAvailable()).isNull();
            mockMvc.perform(authenticated(get("/api/v1/notifications")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(0)))
                    .andExpect(jsonPath("$.unreadCount").value(0));
            jdbc.update("""
                    INSERT INTO notifications
                        (id, recipient_user_id, actor_user_id, feed_id, type, source_action_id)
                    VALUES (?, ?, ?, ?, 'LIKE', ?)
                    """, notice, recipient, actor, feed, UUID.randomUUID());
            mockMvc.perform(authenticated(get("/api/v1/notifications")))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("NOTIFICATION_UNAVAILABLE"));
            mockMvc.perform(authenticated(patch("/api/v1/notifications/" + notice))
                            .cookie(new Cookie("XSRF-TOKEN", "csrf-value")).header("X-XSRF-TOKEN", "csrf-value")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"read\":true}"))
                    .andExpect(status().isNoContent());
            jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", actor);
            mockMvc.perform(authenticated(get("/api/v1/notifications")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].actor").value((Object) null))
                    .andExpect(jsonPath("$.items[0].message", containsString("탈퇴한 회원")))
                    .andExpect(jsonPath("$.items[0].read").value(true))
                    .andExpect(jsonPath("$.unreadCount").value(0));
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", recipient);
            jdbc.update("DELETE FROM users WHERE id = ?", actor);
        }
    }

    private static MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer no-profile-token");
    }
}
