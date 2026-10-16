package com.harudle.common.testdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.category.repository.CategoryRepository;
import com.harudle.comment.repository.CommentRepository;
import com.harudle.feed.query.FeedSort;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.support.MockMvcIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@MockMvcIntegrationTest
@TestPropertySource(properties = {
        "DEPLOY_ENV=dev", "spring.config.import=", "harudle.generation.adapters.enabled=false",
        "spring.security.oauth2.client.registration.kakao.client-id=test",
        "spring.security.oauth2.client.registration.kakao.client-secret=test",
        "app.auth.access-token.secret-base64=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.session.events.log=false"
})
@Transactional
class DevTestDataVisibilityIntegrationTest {
    private static final String TEST_CATEGORY = "부하012345abcdef";
    private static final String CSRF = "visibility-test-csrf";
    private final UUID member = UUID.randomUUID();
    private final UUID testMember = UUID.randomUUID();
    private final UUID reactor = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID last = UUID.randomUUID();
    private final UUID dummy = UUID.randomUUID();
    private final UUID dummyInNormalCategory = UUID.randomUUID();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private CategoryRepository categories;
    @Autowired private CommentRepository comments;
    @MockitoBean private ImageUrlProvider images;
    @MockitoSpyBean private DevTestDataVisibility visibility;
    private long category;
    private long testCategory;
    private long prompt;

    @BeforeEach
    void setUp() {
        doCallRealMethod().when(visibility).enabled();
        assertThat(visibility.enabled()).isTrue();
        createMember(member, null, "일반회원");
        createMember(testMember, "0@0123456789abcdef0123456789abcdef.loadtest.invalid", "테스트회원");
        createMember(reactor, "1@0123456789abcdef0123456789abcdef.loadtest.invalid", "반응회원");
        jdbc.update("INSERT INTO categories (name, sort_order) VALUES (?, 999), ('부하테스트', 999)", TEST_CATEGORY);
        category = jdbc.queryForObject("SELECT id FROM categories WHERE name = '일상'", Long.class);
        testCategory = jdbc.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, TEST_CATEGORY);
        prompt = jdbc.queryForObject("""
                INSERT INTO generation_prompts (storyboard_prompt_text, image_style_prompt_text, image_asset_object_key)
                VALUES ('스토리보드', '이미지', 'references/visibility.png') RETURNING id
                """, Long.class);
        createFeed(first, member, category, "2026-10-15T01:00:00Z", 5);
        createFeed(last, member, category, "2026-10-15T00:00:00Z", 2);
        createFeed(dummy, testMember, testCategory, "2026-10-15T03:00:00Z", 99);
        createFeed(dummyInNormalCategory, testMember, category, "2026-10-15T02:00:00Z", 98);
        jdbc.update("INSERT INTO comments (id, feed_id, author_id, content) VALUES (?, ?, ?, '더미 댓글')",
                UUID.randomUUID(), dummy, testMember);
        when(images.forFeed()).thenReturn(images);
        when(images.createAccessUrl(anyString())).thenReturn(new ImageAccessUrl(
                URI.create("https://example.com/diary.png"), Instant.parse("2026-10-17T12:00:00Z")));
    }

    @ParameterizedTest
    @EnumSource(FeedSort.class)
    void hidesDummyAuthorsBeforePaginationEvenInNormalCategories(FeedSort sort) throws Exception {
        var page = mvc.perform(get("/api/v1/feeds").param("sort", sort.name()).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.hasNext").value(true)).andReturn();
        String cursor = json.readTree(page.getResponse().getContentAsString()).get("nextCursor").asString();
        mvc.perform(get("/api/v1/feeds").param("sort", sort.name()).param("size", "1").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(last.toString()))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/v1/feeds").param("categoryId", Long.toString(category))
                        .with(jwt().jwt(token -> token.subject(member.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/v1/feeds").param("categoryId", Long.toString(testCategory)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void hidesTestCategoriesButKeepsSimilarRealNames() throws Exception {
        for (UUID viewer : new UUID[]{null, member}) {
            var query = get("/api/v1/categories");
            if (viewer != null) query.with(jwt().jwt(token -> token.subject(viewer.toString())));
            var result = mvc.perform(query).andExpect(status().isOk()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain(TEST_CATEGORY).contains("부하테스트");
        }
        mvc.perform(get("/api/v1/categories").with(jwt().jwt(token -> token.subject(reactor.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.name == '" + TEST_CATEGORY + "')]").isNotEmpty());
    }

    @Test
    void directAddressesAreHiddenAndAuthenticatedTestMembersStillReadFixtures() throws Exception {
        for (String suffix : new String[]{"", "/comments"}) {
            for (UUID viewer : new UUID[]{null, member}) {
                var query = get("/api/v1/feeds/" + dummy + suffix).header("X-Load-Test", "true");
                if (viewer != null) query.with(jwt().jwt(token -> token.subject(viewer.toString())));
                mvc.perform(query).andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("FEED_NOT_FOUND"));
            }
            mvc.perform(get("/api/v1/feeds/" + dummy + suffix)
                            .with(jwt().jwt(token -> token.subject(reactor.toString()))))
                    .andExpect(status().isOk());
            mvc.perform(get("/api/v1/feeds/" + first + suffix)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/feeds").param("categoryId", Long.toString(testCategory))
                        .with(jwt().jwt(token -> token.subject(reactor.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(dummy.toString()));
    }

    @Test
    void deletedTestViewerCannotReadAnotherTestMembersData() throws Exception {
        jdbc.update("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?", reactor);
        mvc.perform(get("/api/v1/feeds/{id}", dummy).with(jwt().jwt(token -> token.subject(reactor.toString()))))
                .andExpect(status().isNotFound());
        assertThat(comments.existsVisibleFeed(dummy, reactor)).isFalse();
        assertThat(categories.findVisibleActive(reactor)).noneMatch(item -> item.name().equals(TEST_CATEGORY));
    }

    @Test
    void blocksCrossGroupCommentsAndLikesWithoutChangingRowsOrCounters() throws Exception {
        for (UUID[] pair : new UUID[][]{{member, dummy}, {reactor, first}}) {
            call("POST", "/api/v1/feeds/" + pair[1] + "/comments", pair[0], 404);
            call("PUT", "/api/v1/feeds/" + pair[1] + "/like", pair[0], 404);
            call("DELETE", "/api/v1/feeds/" + pair[1] + "/like", pair[0], 404);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM feed_likes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT like_count FROM feeds WHERE id = ?", Integer.class, first)).isEqualTo(5);
        call("POST", "/api/v1/feeds/" + dummy + "/comments", reactor, 201);
        call("PUT", "/api/v1/feeds/" + dummy + "/like", reactor, 200);
        call("DELETE", "/api/v1/feeds/" + dummy + "/like", reactor, 200);
    }

    @Test
    void keepsOriginalVisibilityOutsideDev() throws Exception {
        assertThat(new DevTestDataVisibility("prod").enabled()).isFalse();
        doReturn(false).when(visibility).enabled();
        call("GET", "/api/v1/feeds/" + dummy, member, 200);
        call("GET", "/api/v1/feeds/" + dummy + "/comments", member, 200);
        mvc.perform(get("/api/v1/feeds").with(jwt().jwt(token -> token.subject(member.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(4));
        mvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.name == '" + TEST_CATEGORY + "')]").isNotEmpty());
    }

    @Test
    void apiStatementCountsDoNotIncreaseIncludingCommentAndLikeMutations() throws Exception {
        for (String endpoint : List.of("/api/v1/categories", "/api/v1/feeds?categoryId=" + testCategory,
                "/api/v1/feeds/" + dummy, "/api/v1/feeds/" + dummy + "/comments")) {
            long before = modeSqlCount(false, () -> call("GET", endpoint, reactor, 200));
            long after = modeSqlCount(true, () -> call("GET", endpoint, reactor, 200));
            assertThat(after).as(endpoint).isEqualTo(before);
        }
        for (String method : List.of("POST", "PUT", "DELETE")) {
            String endpoint = "/api/v1/feeds/" + dummy + (method.equals("POST") ? "/comments" : "/like");
            prepareLike(method);
            long before = modeSqlCount(false, () -> call(method, endpoint, reactor, method.equals("POST") ? 201 : 200));
            prepareLike(method);
            long after = modeSqlCount(true, () -> call(method, endpoint, reactor, method.equals("POST") ? 201 : 200));
            assertThat(after).as(method + " " + endpoint).isEqualTo(before);
        }
    }

    private void prepareLike(String method) {
        if (method.equals("POST")) return;
        jdbc.update("DELETE FROM feed_likes WHERE feed_id = ? AND user_id = ?", dummy, reactor);
        jdbc.update("UPDATE feeds SET like_count = 99 WHERE id = ?", dummy);
        if (method.equals("DELETE")) jdbc.update("INSERT INTO feed_likes (id, feed_id, user_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), dummy, reactor);
    }

    private long modeSqlCount(boolean enabled, Action action) throws Exception {
        doReturn(enabled).when(visibility).enabled();
        entityManager.flush(); entityManager.clear();
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        stats.clear(); action.run(); entityManager.flush();
        return stats.getPrepareStatementCount();
    }

    private void call(String method, String endpoint, UUID viewer, int expectedStatus) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), endpoint)
                        .with(jwt().jwt(token -> token.subject(viewer.toString())))
                        .cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"테스트 댓글\"}"))
                .andExpect(status().is(expectedStatus));
    }

    private void createMember(UUID id, String email, String name) {
        jdbc.update("INSERT INTO users (id, primary_email, name, nickname, profile_image_code) VALUES (?, ?, ?, ?, 1)",
                id, email, name, name);
        jdbc.update("INSERT INTO oauth_accounts (user_id, provider, provider_subject) VALUES (?, 'KAKAO', ?)",
                id, "visibility-" + id);
    }

    private void createFeed(UUID id, UUID author, long categoryId, String at, int likes) {
        UUID diary = UUID.randomUUID();
        jdbc.update("INSERT INTO diaries (id, user_id, diary_date, source_text) VALUES (?, ?, CURRENT_DATE, '테스트 일기')",
                diary, author);
        jdbc.update("""
                INSERT INTO diary_generations (id, diary_id, prompt_id, idempotency_key, request_fingerprint,
                    status, title, image_object_key)
                VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', '공유 제목', ?)
                """, UUID.randomUUID(), diary, prompt, UUID.randomUUID(), "a".repeat(64), "generated/" + id + ".png");
        jdbc.update("INSERT INTO feeds (id, diary_id, category_id, published_at, like_count) VALUES (?, ?, ?, ?, ?)",
                id, diary, categoryId, Timestamp.from(Instant.parse(at)), likes);
    }

    @FunctionalInterface private interface Action { void run() throws Exception; }
}
