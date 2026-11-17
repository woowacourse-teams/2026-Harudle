package com.harudle.admin.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.admin.query.AdminPostcardImage;
import com.harudle.admin.service.AdminPostcardImageService;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ApiExceptionLoggerTestConfiguration;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.SecurityConfig;
import com.harudle.generation.diary.service.exception.DiaryGenerationNotFoundException;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import io.restassured.module.mockmvc.specification.MockMvcRequestSpecification;
import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminPostcardController.class)
@Import({AuthenticatedUserIdResolver.class, ApiExceptionLoggerTestConfiguration.class, ProblemDetailFactory.class,
        TraceIdConfiguration.class, CsrfConfiguration.class, SecurityConfig.class})
class AdminPostcardControllerTest {

    private static final UUID ADMIN = UUID.randomUUID();
    private static final UUID GENERATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final String ORIGINAL_KEY = "harudle/generated/diary-images/dev/2026/11/17/abc/image.png";
    private static final String PATH = "/api/v1/admin/generations/" + GENERATION + "/postcard-image";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private AdminPostcardImageService postcardImages;
    @MockitoBean private ImageUrlProvider imageUrls;
    @MockitoBean private JwtDecoder jwtDecoder;
    @MockitoBean private UserRepository users;

    private User admin;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
        when(jwtDecoder.decode("admin-token")).thenReturn(Jwt.withTokenValue("admin-token")
                .header("alg", "HS256").subject(ADMIN.toString()).issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(1800)).build());
        admin = mock(User.class);
        when(admin.isAdmin()).thenReturn(true);
        when(users.findById(ADMIN)).thenReturn(Optional.of(admin));
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("원본 이미지의 접근 주소와 만화 제목을 저장하지 않는 응답으로 돌려준다")
    void issuesOriginalImageUrl() {
        when(postcardImages.find(GENERATION)).thenReturn(new AdminPostcardImage(ORIGINAL_KEY, "콘티는 천사다"));
        when(imageUrls.forRead()).thenReturn(imageUrls);
        when(imageUrls.createAccessUrl(ORIGINAL_KEY)).thenReturn(new ImageAccessUrl(
                URI.create("https://images.example.test/original"), NOW.plusSeconds(900)));

        MockMvcResponse response = adminRequest().get(PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.header(HttpHeaders.CACHE_CONTROL)).isEqualTo("no-store");
        assertThat(response.jsonPath().getString("imageUrl")).isEqualTo("https://images.example.test/original");
        assertThat(response.jsonPath().getString("expiresAt")).isEqualTo("2026-11-17T03:15:00Z");
        assertThat(response.jsonPath().getString("title")).isEqualTo("콘티는 천사다");
        assertThat(response.asString()).doesNotContain(ORIGINAL_KEY);
    }

    @Test
    @DisplayName("엽서로 만들 수 없는 생성은 404로 알리고 이미지 주소를 발급하지 않는다")
    void rejectsUnavailableGeneration() {
        when(postcardImages.find(GENERATION)).thenThrow(new DiaryGenerationNotFoundException());

        MockMvcResponse response = adminRequest().get(PATH);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.jsonPath().getString("code")).isEqualTo("GENERATION_NOT_FOUND");
        verifyNoInteractions(imageUrls);
    }

    @Test
    @DisplayName("관리자가 아니면 이미지 주소를 조회할 수 없다")
    void rejectsNonAdmin() {
        when(admin.isAdmin()).thenReturn(false);

        assertThat(adminRequest().get(PATH).statusCode()).isEqualTo(403);
        assertThat(RestAssuredMockMvc.given().get(PATH).statusCode()).isEqualTo(401);
        verifyNoInteractions(postcardImages, imageUrls);
    }

    private static MockMvcRequestSpecification adminRequest() {
        return RestAssuredMockMvc.given().header(HttpHeaders.AUTHORIZATION, "Bearer admin-token");
    }
}
