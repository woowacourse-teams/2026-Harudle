package com.harudle.guest.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GuestSessionControllerTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final Duration SESSION_TTL = Duration.ofDays(30);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private GuestSessionTokenHasher tokenHasher;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("Access Token 없이 게스트 세션을 발급하고 HttpOnly Cookie로 전달한다")
    void issuesGuestSessionWithoutAuthentication() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();

        MvcResult result = performIssue(csrfCookie)
                .andExpect(status().isNoContent())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string(""))
                .andExpect(header().string(
                        HttpHeaders.SET_COOKIE,
                        containsString("guest_session=")
                ))
                .andReturn();

        Cookie guestCookie = requireGuestSessionCookie(result);
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertThat(guestCookie.isHttpOnly()).isTrue();
        assertThat(guestCookie.getSecure()).isFalse();
        assertThat(guestCookie.getPath()).isEqualTo("/api/v1/guest");
        assertThat(guestCookie.getMaxAge()).isEqualTo((int) SESSION_TTL.toSeconds());
        assertThat(setCookie)
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Secure");

        GuestSession savedSession = findSession(guestCookie.getValue());
        User savedGuestUser = userRepository
                .findById(savedSession.getGuestUserId())
                .orElseThrow();

        assertThat(savedSession.getExpiresAt())
                .isEqualTo(savedSession.getCreatedAt().plus(SESSION_TTL));
        assertThat(savedGuestUser.getPrimaryEmail()).isNull();
        assertThat(savedGuestUser.getName()).isNotBlank();
        assertThat(guestSessionRepository.count()).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("CSRF Header가 없으면 게스트 세션을 발급하지 않는다")
    void rejectsIssueWithoutCsrfHeader() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();

        MvcResult result = mockMvc.perform(post("/api/v1/guest/session")
                        .cookie(csrfCookie))
                .andExpect(status().isForbidden())
                .andReturn();

        assertThat(result.getResponse().getCookie("guest_session")).isNull();
        assertThat(guestSessionRepository.count()).isZero();
        assertThat(userRepository.count()).isZero();
    }

    @Test
    @DisplayName("유효한 게스트 세션 Cookie가 있으면 같은 세션을 재사용한다")
    void reusesCurrentGuestSession() throws Exception {
        Cookie csrfCookie = issueCsrfCookie();
        MvcResult firstResult = performIssue(csrfCookie)
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie firstGuestCookie = requireGuestSessionCookie(firstResult);

        MvcResult secondResult = mockMvc.perform(post("/api/v1/guest/session")
                        .cookie(csrfCookie, firstGuestCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue()))
                .andExpect(status().isNoContent())
                .andReturn();
        Cookie secondGuestCookie = requireGuestSessionCookie(secondResult);

        assertThat(secondGuestCookie.getValue()).isEqualTo(firstGuestCookie.getValue());
        assertThat(guestSessionRepository.count()).isEqualTo(1);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST 이외의 게스트 세션 요청은 공개하지 않는다")
    void rejectsUnpermittedGuestSessionMethod() throws Exception {
        mockMvc.perform(get("/api/v1/guest/session"))
                .andExpect(status().isUnauthorized());
    }

    private org.springframework.test.web.servlet.ResultActions performIssue(
            Cookie csrfCookie
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/guest/session")
                .cookie(csrfCookie)
                .header("X-XSRF-TOKEN", csrfCookie.getValue()));
    }

    private Cookie issueCsrfCookie() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();

        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertThat(csrfCookie).isNotNull();
        assertThat(csrfCookie.getPath()).isEqualTo("/api/v1");
        return csrfCookie;
    }

    private Cookie requireGuestSessionCookie(MvcResult result) {
        Cookie guestCookie = result.getResponse().getCookie("guest_session");
        assertThat(guestCookie).isNotNull();
        return guestCookie;
    }

    private GuestSession findSession(String rawToken) {
        return guestSessionRepository
                .findByTokenHash(tokenHasher.hash(rawToken))
                .orElseThrow();
    }
}
