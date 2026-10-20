package com.harudle.auth.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.auth.application.AccessTokenService;
import com.harudle.auth.application.ProfileImageStorageException;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.CwebpProfileImageEncoder;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.support.MockMvcIntegrationTest;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@MockMvcIntegrationTest
@TestPropertySource(properties = "harudle.profile.cleanup-interval=1d")
class ProfileImageUploadControllerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final byte[] WEBP = {1, 2, 3};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository users;
    @Autowired
    private OAuthAccountRepository accounts;
    @Autowired
    private AccessTokenService accessTokens;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private CwebpProfileImageEncoder encoder;
    @MockitoBean
    private S3ProfileImageStorage storage;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        when(encoder.encode(any(BufferedImage.class))).thenReturn(WEBP);
        when(storage.store(any(UUID.class), any(byte[].class)))
                .thenAnswer(invocation -> key(invocation.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM profile_image_cleanup_tasks");
        users.deleteAll();
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("인증된 파일 업로드는 CSRF 없이 정사각형 변환과 DB 저장 후 204를 반환한다")
    void uploadsImageAndChangesOnlyCurrentUser() throws IOException {
        User user = saveUser(true);
        User other = saveUser(true);

        MockMvcResponse response = upload(user, imageBytes("png", 1600, 800));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(response.body().asString()).isEmpty();
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        User saved = users.findById(user.getId()).orElseThrow();
        assertThat(saved.getProfileImageObjectKey()).isEqualTo(key(user.getId()));
        assertThat(saved.getNickname()).isEqualTo(user.getNickname());
        assertThat(saved.getProfileImageCode()).isEqualTo((short) 3);
        assertThat(users.findById(other.getId()).orElseThrow().getProfileImageObjectKey()).isNull();
        ArgumentCaptor<BufferedImage> captor = ArgumentCaptor.forClass(BufferedImage.class);
        verify(encoder).encode(captor.capture());
        assertThat(captor.getValue().getWidth()).isEqualTo(512);
        assertThat(captor.getValue().getHeight()).isEqualTo(512);
        verify(storage).store(user.getId(), WEBP);
    }

    @Test
    @DisplayName("기존 개인 사진을 교체하면 이전 파일을 삭제한다")
    void replacesUploadedImage() throws IOException {
        User user = saveUser(true);
        String previous = setPreviousImage(user);

        MockMvcResponse response = upload(user, imageBytes("jpeg", 100, 100));

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageObjectKey()).isEqualTo(key(user.getId()));
        verify(storage).delete(previous);
    }

    @Test
    @DisplayName("기본 이미지 선택 API도 이전 개인 사진을 정리한다")
    void switchesBackToDefault() {
        User user = saveUser(true);
        String previous = setPreviousImage(user);

        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearer(user.getId()))
                .contentType(MediaType.APPLICATION_JSON_VALUE)
                .body(Map.of("imageCode", 7))
                .patch("/api/v1/me/profile-image");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageObjectKey()).isNull();
        verify(storage).delete(previous);
        verifyNoInteractions(encoder);
    }

    @Test
    @DisplayName("로그인하지 않으면 업로드할 수 없다")
    void requiresBearerAuthentication() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .multiPart("image", "profile.png", new byte[] {1}, "image/png")
                .post("/api/v1/me/profile-image");

        assertThat(response.statusCode()).isEqualTo(401);
        verifyNoInteractions(encoder, storage);
    }

    @Test
    @DisplayName("image 항목이 누락되면 요청을 거절한다")
    void requiresImagePart() {
        User user = saveUser(true);
        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearer(user.getId()))
                .multiPart("other", "profile.png", new byte[] {1}, "image/png")
                .post("/api/v1/me/profile-image");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(encoder, storage);
    }

    @Test
    @DisplayName("빈 파일은 기존 프로필을 유지하며 거절한다")
    void rejectsEmptyImage() {
        User user = saveUser(true);
        MockMvcResponse response = upload(user, new byte[0]);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.jsonPath().getString("code")).isEqualTo("VALIDATION_ERROR");
        verifyNoInteractions(encoder, storage);
    }

    @Test
    @DisplayName("원본 5MiB 제한은 다른 업로드 설정과 별도로 적용한다")
    void rejectsOversizedImage() {
        User user = saveUser(true);
        MockMvcResponse response = upload(user, new byte[5 * 1024 * 1024 + 1]);

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.jsonPath().getString("code")).isEqualTo("PAYLOAD_TOO_LARGE");
        verifyNoInteractions(encoder, storage);
    }

    @Test
    @DisplayName("파일명과 Content-Type이 PNG여도 실제 GIF는 거절한다")
    void rejectsDisguisedGif() throws IOException {
        User user = saveUser(true);
        MockMvcResponse response = upload(user, imageBytes("gif", 10, 10));

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.jsonPath().getString("code")).isEqualTo("UNSUPPORTED_MEDIA_TYPE");
        verifyNoInteractions(encoder, storage);
    }

    @Test
    @DisplayName("공개 프로필이 없는 회원은 파일을 처리하지 않는다")
    void rejectsUnassignedProfile() {
        User user = saveUser(false);
        MockMvcResponse response = upload(user, new byte[] {1});

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(response.jsonPath().getString("code")).isEqualTo("PUBLIC_PROFILE_NOT_ASSIGNED");
        verifyNoInteractions(encoder, storage);
    }

    @Test
    @DisplayName("존재하지 않는 회원의 토큰은 파일을 처리하지 않는다")
    void rejectsMissingUser() {
        MockMvcResponse response = RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearer(UUID.randomUUID()))
                .multiPart("image", "profile.png", new byte[] {1}, "image/png")
                .post("/api/v1/me/profile-image");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INVALID_CURRENT_USER");
        verifyNoInteractions(encoder, storage);
    }

    @Test
    @DisplayName("S3 저장 실패는 기존 사진을 유지하며 503 오류를 반환하고 파일 경로를 응답에 노출하지 않는다")
    void reportsStorageFailureWithoutChangingCurrentImage() throws IOException {
        User user = saveUser(true);
        String previous = setPreviousImage(user);
        String newKey = key(user.getId());
        when(storage.store(any(UUID.class), any(byte[].class)))
                .thenThrow(new ProfileImageStorageException("upload failed", newKey, null));

        MockMvcResponse response = upload(user, imageBytes("png", 10, 10));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.jsonPath().getString("code")).isEqualTo("PROFILE_IMAGE_STORAGE_ERROR");
        assertThat(response.body().asString()).doesNotContain(newKey, previous);
        assertThat(users.findById(user.getId()).orElseThrow().getProfileImageObjectKey()).isEqualTo(previous);
        verify(storage, never()).delete(newKey);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profile_image_cleanup_tasks WHERE object_key = ?",
                Integer.class, newKey)).isEqualTo(1);
    }

    private MockMvcResponse upload(User user, byte[] bytes) {
        return RestAssuredMockMvc.given()
                .header(HttpHeaders.AUTHORIZATION, bearer(user.getId()))
                .multiPart("image", "profile.png", bytes, "image/png")
                .post("/api/v1/me/profile-image");
    }

    private User saveUser(boolean assigned) {
        User user = new User(null, "OAuth이름", NOW);
        if (assigned) {
            user.assignInitialPublicProfile("회원" + user.getId().toString().substring(0, 6), 3, NOW);
        }
        user = users.save(user);
        accounts.save(new OAuthAccount(user, OAuthProvider.KAKAO, user.getId().toString(), null, NOW));
        return user;
    }

    private String setPreviousImage(User user) {
        String previous = "harudle/profile-images/dev/" + user.getId()
                + "/11111111-1111-1111-1111-111111111111.webp";
        user.changeUploadedProfileImage(previous, NOW);
        users.save(user);
        return previous;
    }

    private String bearer(UUID userId) {
        return "Bearer " + accessTokens.issue(userId, UUID.randomUUID(), Instant.now()).accessToken();
    }

    private static String key(UUID userId) {
        return "harudle/profile-images/dev/" + userId + "/22222222-2222-2222-2222-222222222222.webp";
    }

    private byte[] imageBytes(String format, int width, int height) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BufferedImage source = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageIO.write(source, format, output)).isTrue();
        return output.toByteArray();
    }
}
