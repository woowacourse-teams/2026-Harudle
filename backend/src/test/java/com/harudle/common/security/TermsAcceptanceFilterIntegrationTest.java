package com.harudle.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.port.AccountRestrictionReader;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.ProblemDetailResponseWriter;
import com.harudle.common.error.TraceIdConfiguration;
import com.harudle.legal.application.LegalDocumentCatalog;
import com.harudle.legal.application.TermsAcceptance;
import com.harudle.legal.application.TermsAcceptanceCommand;
import com.harudle.legal.application.TermsAcceptanceRepository;
import com.harudle.legal.application.TermsAcceptanceService;
import com.harudle.legal.domain.LegalDocumentType;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

class TermsAcceptanceFilterIntegrationTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.config.name=terms-filter-test", "logging.level.root=WARN",
                    "spring.security.filter.order=-80", "harudle.legal.require-terms-acceptance=true"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class Enforced extends HttpContext {
        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/reports", "/api/v1/notifications", "/api/v1/future/member", "/api/v1/feeds"})
        @DisplayName("모든 Bearer 보안 체인에서 미수락 회원은 controller 실행 전에 동의 필요 403을 받는다")
        void guardsCurrentAndFutureSecurityChains(String path) throws Exception {
            HttpResponse<String> response = request("GET", path, "member");

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.body()).contains("TERMS_ACCEPTANCE_REQUIRED");
            assertThat(controller.calls.get()).isZero();
            assertThat(members.reads.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("기존 Bearer도 현재 약관 수락 전에는 거절하고 수락 후 같은 토큰으로 허용한다")
        void appliesAcceptanceToEachRequestWithoutIssuingANewToken() throws Exception {
            assertThat(request("GET", "/api/v1/future/member", "member").statusCode()).isEqualTo(403);
            var terms = catalog.current(LegalDocumentType.TERMS);
            var privacy = catalog.current(LegalDocumentType.PRIVACY);
            service.accept(USER_ID, new TermsAcceptanceCommand(terms.version(), terms.contentHash(),
                    privacy.version(), privacy.contentHash(), true, true));

            assertThat(request("GET", "/api/v1/future/member", "member").statusCode()).isEqualTo(200);
            assertThat(controller.calls.get()).isEqualTo(1);
        }

        @ParameterizedTest
        @CsvSource({
                "GET, /api/v1/me", "GET, /api/v1/me/agreements", "PUT, /api/v1/me/agreements",
                "GET, /api/v1/auth/csrf", "POST, /api/v1/auth/logout", "POST, /api/v1/auth/refresh",
                "GET, /api/v1/legal/documents", "GET, /api/v1/legal/documents/terms/2026-10-22"
        })
        @DisplayName("동의 조회·제출·본인 확인·문서·세션 정리 경로는 약관 저장소 장애 중에도 필터가 막지 않는다")
        void keepsAcceptanceAndSessionCleanupEndpointsReachable(String method, String path) throws Exception {
            members.unavailable.set(true);

            assertThat(request(method, path, "member").statusCode()).isEqualTo(200);
            assertThat(members.reads.get()).isZero();
            assertThat(controller.calls.get()).isEqualTo(1);
        }

        @ParameterizedTest
        @CsvSource({"POST, /api/v1/me", "DELETE, /api/v1/me/agreements", "POST, /api/v1/legal/documents"})
        @DisplayName("예외 경로와 같은 URL이라도 허용한 HTTP 메서드가 아니면 동의 검사를 우회하지 않는다")
        void scopesExemptionsToTheIntendedHttpMethods(String method, String path) throws Exception {
            HttpResponse<String> response = request(method, path, "member");

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("TERMS_ACCEPTANCE_REQUIRED");
            assertThat(members.reads.get()).isEqualTo(1);
            assertThat(controller.calls.get()).isZero();
        }

        @Test
        @DisplayName("공개 열람은 익명 사용자의 약관 상태를 조회하지 않고 Bearer 요청만 검사한다")
        void preservesAnonymousPublicReading() throws Exception {
            assertThat(request("GET", "/api/v1/feeds", null).statusCode()).isEqualTo(200);
            assertThat(members.reads.get()).isZero();
            assertThat(request("GET", "/api/v1/feeds", "member").statusCode()).isEqualTo(403);
            assertThat(members.reads.get()).isEqualTo(1);
            assertThat(controller.calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("활성 OAuth 회원이 아닌 Bearer 주체는 동의 필요 대신 인증 오류로 거절한다")
        void rejectsInactiveOrUnlinkedMembership() throws Exception {
            members.active.set(false);
            HttpResponse<String> response = request("GET", "/api/v1/reports", "member");

            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).contains("UNAUTHORIZED").doesNotContain("TERMS_ACCEPTANCE_REQUIRED");
            assertThat(controller.calls.get()).isZero();
        }

        @Test
        @DisplayName("약관 저장소 장애는 동의 필요로 오인하지 않고 상세를 감춘 500으로 닫힌 상태를 유지한다")
        void failsClosedWhenTheRepositoryIsUnavailable() throws Exception {
            members.unavailable.set(true);
            HttpResponse<String> response = request("GET", "/api/v1/future/member", "member");

            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(response.body()).contains("INTERNAL_SERVER_ERROR")
                    .doesNotContain("TERMS_ACCEPTANCE_REQUIRED", "secret database error");
            assertThat(members.reads.get()).isEqualTo(1);
            assertThat(controller.calls.get()).isZero();
        }

        @Test
        @DisplayName("정지된 계정은 약관 상태보다 먼저 거절해 동의 화면을 통해 제재를 우회하지 않는다")
        void checksAccountSuspensionBeforeTermsAcceptance() throws Exception {
            restrictions.suspended.set(true);
            members.unavailable.set(true);
            HttpResponse<String> response = request("GET", "/api/v1/reports", "member");

            assertThat(response.statusCode()).isEqualTo(403);
            assertThat(response.body()).contains("ACCOUNT_SUSPENDED").doesNotContain("TERMS_ACCEPTANCE_REQUIRED");
            assertThat(members.reads.get()).isZero();
            assertThat(controller.calls.get()).isZero();
        }

        @Test
        @DisplayName("정지된 Bearer도 공개 약관은 읽지만 일반 회원 API는 계속 제재 403으로 거절한다")
        void letsSuspendedMembersReadPublicDocumentsWithoutRestoringMemberAccess() throws Exception {
            restrictions.suspended.set(true);
            members.unavailable.set(true);

            assertThat(request("GET", "/api/v1/legal/documents", "member").statusCode()).isEqualTo(200);
            assertThat(request("GET", "/api/v1/legal/documents/terms/2026-10-22", "member").statusCode())
                    .isEqualTo(200);
            HttpResponse<String> protectedResponse = request("GET", "/api/v1/reports", "member");

            assertThat(protectedResponse.statusCode()).isEqualTo(403);
            assertThat(protectedResponse.body()).contains("ACCOUNT_SUSPENDED")
                    .doesNotContain("TERMS_ACCEPTANCE_REQUIRED");
            assertThat(members.reads.get()).isZero();
            assertThat(controller.calls.get()).isEqualTo(2);
        }

        @Test
        @DisplayName("검증되지 않은 Bearer는 기존 보안 체인에서 거절하고 약관 저장소를 조회하지 않는다")
        void preservesInvalidBearerRejection() throws Exception {
            assertThat(request("GET", "/api/v1/reports", "invalid").statusCode()).isEqualTo(401);
            assertThat(members.reads.get()).isZero();
            assertThat(controller.calls.get()).isZero();
        }
    }

    @Nested
    @SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
            properties = {"spring.config.name=terms-filter-test", "logging.level.root=WARN",
                    "spring.security.filter.order=-80", "harudle.legal.require-terms-acceptance=false"})
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    class Disabled extends HttpContext {
        @ParameterizedTest
        @ValueSource(strings = {"/api/v1/reports", "/api/v1/future/member", "/api/v1/feeds"})
        @DisplayName("필수 적용이 꺼져 있으면 미수락이나 약관 저장소 장애로 기존 API를 막지 않는다")
        void bypassesTermsChecksUntilEnforcementIsEnabled(String path) throws Exception {
            members.unavailable.set(true);

            assertThat(request("GET", path, "member").statusCode()).isEqualTo(200);
            assertThat(members.reads.get()).isZero();
            assertThat(controller.calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("실제로 바인딩된 비활성 설정은 이력이 없는 회원에게도 필수 수락 상태를 요구하지 않는다")
        void reportsDisabledEnforcementWithoutInventingAnAcceptance() {
            var status = service.status(USER_ID);

            assertThat(status.enforcementEnabled()).isFalse();
            assertThat(status.minimumAge()).isEqualTo(14);
            assertThat(status.requiresAcceptance()).isFalse();
            assertThat(status.acceptedAt()).isNull();
            assertThat(members.saved.get()).isNull();
        }
    }

    abstract static class HttpContext {
        @Value("${local.server.port}") int port;
        @Autowired Members members;
        @Autowired Restrictions restrictions;
        @Autowired TestController controller;
        @Autowired TermsAcceptanceService service;
        @Autowired LegalDocumentCatalog catalog;

        @BeforeEach
        void reset() {
            members.active.set(true);
            members.unavailable.set(false);
            members.reads.set(0);
            members.saved.set(null);
            restrictions.suspended.set(false);
            controller.calls.set(0);
        }

        HttpResponse<String> request(String method, String path, String token) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(10));
            if (token != null) request.header("Authorization", "Bearer " + token);
            return CLIENT.send(request.method(method, HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    @Import({TermsAcceptanceConfiguration.class, TermsAcceptanceService.class, LegalDocumentCatalog.class,
            AccountSuspensionConfiguration.class, AccountAccessPolicy.class,
            ProblemDetailFactory.class, TraceIdConfiguration.class})
    static class TestApplication {
        @Bean Members members() { return new Members(); }
        @Bean Restrictions restrictions() { return new Restrictions(); }
        @Bean TestController controller() { return new TestController(); }
        @Bean Clock authClock() { return Clock.fixed(Instant.parse("2026-10-25T06:00:00Z"), ZoneOffset.UTC); }
        @Bean ProblemDetailResponseWriter problems(ProblemDetailFactory factory, ObjectMapper mapper) {
            return new ProblemDetailResponseWriter(factory, mapper);
        }
        @Bean JwtDecoder decoder() {
            return token -> {
                if ("invalid".equals(token)) throw new BadJwtException("invalid");
                return Jwt.withTokenValue(token).header("alg", "HS256").subject(USER_ID.toString()).build();
            };
        }
        @Bean @Order(0)
        SecurityFilterChain futureChain(HttpSecurity http) throws Exception {
            return http.securityMatcher("/api/v1/future/**")
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .csrf(AbstractHttpConfigurer::disable)
                    .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())).build();
        }
        @Bean @Order(1)
        SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
            return http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.requestMatchers("/api/v1/feeds", "/api/v1/auth/**",
                            "/api/v1/legal/**").permitAll().anyRequest().authenticated())
                    .csrf(AbstractHttpConfigurer::disable)
                    .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults())).build();
        }
    }

    static class Members implements TermsAcceptanceRepository {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean unavailable = new AtomicBoolean();
        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicReference<TermsAcceptance> saved = new AtomicReference<>();

        @Override
        public Optional<MemberState> findMember(UUID userId, String version, String contentHash) {
            reads.incrementAndGet();
            if (unavailable.get()) throw new DataAccessResourceFailureException("secret database error");
            if (!active.get()) return Optional.empty();
            return Optional.of(new MemberState(Optional.ofNullable(saved.get())
                    .filter(acceptance -> acceptance.termsVersion().equals(version)
                            && acceptance.termsContentHash().equals(contentHash))));
        }
        @Override
        public void insert(UUID userId, TermsAcceptance acceptance) {
            saved.compareAndSet(null, acceptance);
        }
    }

    static class Restrictions implements AccountRestrictionReader {
        private final AtomicBoolean suspended = new AtomicBoolean();
        @Override public boolean isSuspended(UUID userId) {
            throw new AssertionError("일반 요청에서 토큰 발급용 잠금 조회를 사용하면 안 됩니다.");
        }
        @Override public boolean isSuspendedForRequest(UUID userId) { return suspended.get(); }
    }

    @RestController
    @TestComponent
    static class TestController {
        private final AtomicInteger calls = new AtomicInteger();
        @RequestMapping({"/api/v1/me", "/api/v1/me/agreements", "/api/v1/reports", "/api/v1/notifications",
                "/api/v1/feeds", "/api/v1/auth/csrf", "/api/v1/auth/logout", "/api/v1/auth/refresh",
                "/api/v1/future/member", "/api/v1/legal/documents",
                "/api/v1/legal/documents/terms/2026-10-22"})
        String call() {
            calls.incrementAndGet();
            return "ok";
        }
    }
}
