package com.harudle.guest.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.diary.domain.Diary;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.guest.domain.GuestSession;
import java.time.Instant;
import java.time.LocalDate;
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
class GuestSessionConcurrencyTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18-alpine");
    private static final String TOKEN_HASH = "a".repeat(64);
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");
    private static final Instant USED_AT = Instant.parse("2026-08-20T00:00:00Z");
    private static final Instant EXPIRES_AT = Instant.parse("2026-09-19T00:00:00Z");
    private static final LocalDate DIARY_DATE = LocalDate.of(2026, 8, 20);

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer POSTGRESQL_CONTAINER = new PostgreSQLContainer(POSTGRES_IMAGE);

    @Autowired
    private GuestSessionRepository guestSessionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DiaryRepository diaryRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("동시에 같은 게스트 세션을 사용하면 하나의 일기만 연결된다")
    void serializesConcurrentGuestSessionUsage() throws Exception {
        User guestUser = userRepository.saveAndFlush(new User(null, "게스트", CREATED_AT));
        List<UUID> diaryIds = saveDiaries(guestUser.getId());
        guestSessionRepository.saveAndFlush(GuestSession.create(
                guestUser.getId(),
                TOKEN_HASH,
                EXPIRES_AT,
                CREATED_AT
        ));

        ExecutorService executorService = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<Boolean>> futures = IntStream.range(0, diaryIds.size())
                    .mapToObj(index -> executorService.submit(() -> {
                        ready.countDown();
                        start.await();
                        return useSessionForDiary(diaryIds.get(index));
                    }))
                    .toList();

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Boolean> results = futures.stream()
                    .map(this::getResult)
                    .toList();

            assertThat(results).containsExactlyInAnyOrder(true, false);

            GuestSession savedSession = guestSessionRepository
                    .findByTokenHash(TOKEN_HASH)
                    .orElseThrow();
            assertThat(savedSession.isUsed()).isTrue();
            assertThat(savedSession.getDiaryId()).isIn(diaryIds);
            assertThat(savedSession.getUsedAt()).isEqualTo(USED_AT);
        } finally {
            executorService.shutdownNow();
        }
    }

    private List<UUID> saveDiaries(UUID guestUserId) {
        List<Diary> diaries = List.of(
                Diary.create(guestUserId, DIARY_DATE, "첫 번째 일기"),
                Diary.create(guestUserId, DIARY_DATE, "두 번째 일기")
        );
        return diaryRepository.saveAllAndFlush(diaries).stream()
                .map(Diary::getId)
                .toList();
    }

    private boolean useSessionForDiary(UUID diaryId) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                GuestSession session = guestSessionRepository
                        .findByTokenHashForUpdate(TOKEN_HASH)
                        .orElseThrow();
                session.useForDiary(diaryId, USED_AT);
            });
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    private boolean getResult(Future<Boolean> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new AssertionError("동시 게스트 세션 사용 결과를 확인하지 못했습니다.", exception);
        }
    }
}
