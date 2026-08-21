package com.harudle.diary.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.service.dto.CreateGuestDiaryCommand;
import com.harudle.guest.application.exception.GuestTrialAlreadyUsedException;
import com.harudle.guest.domain.GuestSession;
import com.harudle.guest.infrastructure.token.GuestSessionTokenHasher;
import com.harudle.guest.repository.GuestSessionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@Import(GuestDiaryCreationConcurrencyTest.FixedClockConfiguration.class)
class GuestDiaryCreationConcurrencyTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final String RAW_TOKEN = "guest-session-token";
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 20);

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private GuestDiaryCreationTransactionService transactionService;

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private GuestSessionTokenHasher tokenHasher;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("동시에 다른 멱등성 키로 생성해도 게스트 일기와 생성 기록은 하나만 선점한다")
    void createOnlyOneDiaryForConcurrentGuestRequests() throws Exception {
        User guestUser = userRepository.saveAndFlush(new User(null, "게스트", CREATED_AT));
        String tokenHash = tokenHasher.hash(RAW_TOKEN);
        guestSessionRepository.saveAndFlush(GuestSession.create(
                guestUser.getId(),
                tokenHash,
                EXPIRES_AT,
                CREATED_AT
        ));
        insertGenerationPrompt();

        List<CreateGuestDiaryCommand> commands = List.of(
                createCommand(UUID.fromString("7e5cc251-fdde-4cc0-a54e-2c8142750609")),
                createCommand(UUID.fromString("11b8a64c-6380-46b2-8670-f92a671f14f2"))
        );
        ExecutorService executorService = Executors.newFixedThreadPool(commands.size());
        CountDownLatch ready = new CountDownLatch(commands.size());
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> futures = commands.stream()
                    .map(command -> executorService.submit(() -> {
                        ready.countDown();
                        start.await();
                        return tryClaim(command);
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Boolean> results = futures.stream()
                    .map(this::getResult)
                    .toList();

            assertThat(results).containsExactlyInAnyOrder(true, false);
            assertThat(countRows("diaries")).isEqualTo(1);
            assertThat(countRows("diary_generations")).isEqualTo(1);
            assertThat(countRows("daily_generation_usage")).isZero();

            GuestSession savedSession = guestSessionRepository.findByTokenHash(tokenHash)
                    .orElseThrow();
            assertThat(savedSession.isUsed()).isTrue();
            assertThat(savedSession.getDiaryId()).isNotNull();
            assertThat(savedSession.getUsedAt()).isEqualTo(NOW);
            assertThat(findDiaryOwner(savedSession.getDiaryId())).isEqualTo(guestUser.getId());
        } finally {
            executorService.shutdownNow();
        }
    }

    private boolean tryClaim(CreateGuestDiaryCommand command) {
        try {
            transactionService.claim(RAW_TOKEN, command, true);
            return true;
        } catch (GuestTrialAlreadyUsedException exception) {
            return false;
        }
    }

    private boolean getResult(Future<Boolean> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("동시 게스트 생성 결과를 확인하지 못했습니다.", exception);
        }
    }

    private CreateGuestDiaryCommand createCommand(UUID idempotencyKey) {
        return new CreateGuestDiaryCommand(
                DIARY_DATE,
                "오늘 친구와 카페에 갔다.",
                idempotencyKey
        );
    }

    private void insertGenerationPrompt() {
        executeUpdate("""
                INSERT INTO generation_prompts (
                    storyboard_prompt_text,
                    image_style_prompt_text,
                    image_asset_object_key
                )
                VALUES (?, ?, ?)
                """, "스토리보드 프롬프트", "이미지 스타일 프롬프트", "references/guest-style.png");
    }

    private int countRows(String tableName) {
        String statement = "SELECT COUNT(*) FROM " + tableName;
        return transactionTemplate.execute(status -> {
            Number count = (Number) entityManager.createNativeQuery(statement).getSingleResult();
            return count.intValue();
        });
    }

    private UUID findDiaryOwner(UUID diaryId) {
        return transactionTemplate.execute(status -> (UUID) entityManager
                .createNativeQuery("SELECT user_id FROM diaries WHERE id = ?")
                .setParameter(1, diaryId)
                .getSingleResult());
    }

    private void executeUpdate(String statement, Object... parameters) {
        transactionTemplate.executeWithoutResult(status -> {
            Query query = entityManager.createNativeQuery(statement);
            IntStream.range(0, parameters.length)
                    .forEach(index -> query.setParameter(index + 1, parameters[index]));
            query.executeUpdate();
        });
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        @Qualifier("authClock")
        Clock fixedAuthClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
