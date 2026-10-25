package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.port.AccountRestrictionReader;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.ProblemDetailResponseWriter;
import com.harudle.common.error.TraceIdConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = AccountSuspensionFilterIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=account-suspension-test", "logging.level.root=WARN", "spring.security.filter.order=-80"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AccountSuspensionFilterIntegrationTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${local.server.port}") private int port;
    @Autowired private Restrictions restrictions;
    @Autowired private TestController controller;

    @BeforeEach
    void reset() {
        restrictions.suspended.set(false);
        restrictions.unavailable.set(false);
        restrictions.requestReads.set(0);
        controller.calls.set(0);
    }

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me", "/api/v1/reports", "/api/v1/notifications", "/api/v1/future/member"})
    @DisplayName("실제 servlet 순서에서 모든 보안 체인의 Bearer 회원을 controller 호출 전에 거절한다")
    void guardsExistingAndFutureChainsBeforeController(String path) throws Exception {
        restrictions.suspended.set(true);
        HttpResponse<String> response = get(path, "member");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).contains("ACCOUNT_SUSPENDED");
        assertThat(controller.calls.get()).isZero();
        assertThat(restrictions.requestReads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("기존 access token도 다음 요청부터 막고 해제 뒤 같은 토큰은 다시 허용한다")
    void appliesChangedRestrictionOnEachRequest() throws Exception {
        assertThat(get("/api/v1/me", "member").statusCode()).isEqualTo(200);
        restrictions.suspended.set(true);
        assertThat(get("/api/v1/me", "member").statusCode()).isEqualTo(403);
        restrictions.suspended.set(false);
        assertThat(get("/api/v1/me", "member").statusCode()).isEqualTo(200);
        assertThat(controller.calls.get()).isEqualTo(2);
        assertThat(restrictions.requestReads.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("공개 요청에 Bearer가 없으면 제재 조회 없이 허용하고 같은 회원의 Bearer 요청은 거절한다")
    void preservesAnonymousPublicReading() throws Exception {
        restrictions.suspended.set(true);
        assertThat(get("/api/v1/feeds", null).statusCode()).isEqualTo(200);
        assertThat(restrictions.requestReads.get()).isZero();
        assertThat(get("/api/v1/feeds", "member").statusCode()).isEqualTo(403);
        assertThat(restrictions.requestReads.get()).isEqualTo(1);
        assertThat(controller.calls.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/auth/csrf", "/api/v1/auth/logout"})
    @DisplayName("제재된 Bearer가 있어도 CSRF 발급과 로그아웃 정리는 허용한다")
    void preservesCleanupEndpoints(String path) throws Exception {
        restrictions.suspended.set(true);
        assertThat(get(path, "member").statusCode()).isEqualTo(200);
        assertThat(restrictions.requestReads.get()).isZero();
        assertThat(controller.calls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("제재 조회 장애는 controller를 실행하지 않고 정지와 구별한 안전한 500으로 응답한다")
    void failsClosedWithoutMisclassifyingDatabaseFailure() throws Exception {
        restrictions.unavailable.set(true);
        HttpResponse<String> response = get("/api/v1/me", "member");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.body()).contains("INTERNAL_SERVER_ERROR").doesNotContain("ACCOUNT_SUSPENDED", "secret");
        assertThat(restrictions.requestReads.get()).isEqualTo(1);
        assertThat(controller.calls.get()).isZero();
    }

    @Test
    @DisplayName("잘못된 Bearer는 기존 보안 체인에서 거절하고 제재 저장소를 조회하지 않는다")
    void preservesInvalidBearerRejection() throws Exception {
        assertThat(get("/api/v1/me", "invalid").statusCode()).isEqualTo(401);
        assertThat(restrictions.requestReads.get()).isZero();
        assertThat(controller.calls.get()).isZero();
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return CLIENT.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    @Import({AccountSuspensionConfiguration.class, AccountAccessPolicy.class, ProblemDetailFactory.class,
            TraceIdConfiguration.class})
    static class TestApplication {
        @Bean
        Restrictions restrictions() {
            return new Restrictions();
        }
        @Bean
        TestController controller() {
            return new TestController();
        }
        @Bean
        ProblemDetailResponseWriter problems(ProblemDetailFactory factory, ObjectMapper mapper) {
            return new ProblemDetailResponseWriter(factory, mapper);
        }
        @Bean
        JwtDecoder decoder() {
            return token -> {
                if ("invalid".equals(token)) {
                    throw new BadJwtException("invalid");
                }
                return Jwt.withTokenValue(token).header("alg", "HS256").subject(USER_ID.toString()).build();
            };
        }
        @Bean
        @Order(0)
        SecurityFilterChain futureChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/future/**")
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .csrf(AbstractHttpConfigurer::disable)
                    .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())).build();
        }
        @Bean
        @Order(1)
        SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
            return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/feeds", "/api/v1/auth/**").permitAll()
                            .anyRequest().authenticated())
                    .csrf(AbstractHttpConfigurer::disable)
                    .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())).build();
        }
    }

    static class Restrictions implements AccountRestrictionReader {
        private final AtomicBoolean suspended = new AtomicBoolean();
        private final AtomicBoolean unavailable = new AtomicBoolean();
        private final AtomicInteger requestReads = new AtomicInteger();

        @Override
        public boolean isSuspended(UUID userId) {
            throw new AssertionError("일반 Bearer 요청은 토큰 발급용 잠금 조회를 사용하면 안 됩니다.");
        }

        @Override
        public boolean isSuspendedForRequest(UUID userId) {
            requestReads.incrementAndGet();
            if (unavailable.get()) {
                throw new DataAccessResourceFailureException("secret database error");
            }
            return suspended.get();
        }
    }

    @RestController
    @TestComponent
    static class TestController {
        private final AtomicInteger calls = new AtomicInteger();
        @RequestMapping({"/api/v1/me", "/api/v1/reports", "/api/v1/notifications", "/api/v1/feeds",
                "/api/v1/auth/csrf", "/api/v1/auth/logout", "/api/v1/future/member"})
        String call() {
            calls.incrementAndGet();
            return "ok";
        }
    }
}
