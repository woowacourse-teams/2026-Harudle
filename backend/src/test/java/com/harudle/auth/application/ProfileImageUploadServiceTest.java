package com.harudle.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.TestcontainersConfiguration;
import com.harudle.auth.domain.OAuthAccount;
import com.harudle.auth.domain.OAuthProvider;
import com.harudle.auth.domain.User;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.ProfileImageCleanupRepository;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.common.security.AuthTimeConfiguration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, AuthTimeConfiguration.class, ProfileImageChangeService.class,
        ProfileImageUploadService.class, ProfileImageCleanupService.class, ProfileImageCleanupRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfileImageUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final byte[] WEBP = {1, 2, 3};

    @Autowired
    private ProfileImageUploadService service;
    @Autowired
    private ProfileImageChangeService changes;
    @Autowired
    private ProfileImageCleanupService cleaner;
    @Autowired
    private UserRepository users;
    @Autowired
    private OAuthAccountRepository accounts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private ProfileImageProcessor processor;
    @MockitoBean
    private S3ProfileImageStorage storage;

    private User user;
    private String oldKey;
    private String newKey;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        user = new User(null, "OAuth이름", NOW);
        user.assignInitialPublicProfile("초록", 3, NOW);
        oldKey = key(user.getId(), "11111111-1111-1111-1111-111111111111");
        newKey = key(user.getId(), "22222222-2222-2222-2222-222222222222");
        user.changeUploadedProfileImage(oldKey, NOW);
        user = users.save(user);
        accounts.save(new OAuthAccount(user, OAuthProvider.KAKAO, user.getId().toString(), null, NOW));
        when(processor.process(any(byte[].class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return WEBP;
        });
        when(storage.store(eq(user.getId()), any(byte[].class))).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return newKey;
        });
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    @DisplayName("DB 교체가 커밋된 뒤 이전 파일을 삭제하고 닉네임과 기본 이미지 번호는 유지한다")
    void uploadsAndDeletesPreviousImageAfterCommit() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(saved().getProfileImageObjectKey()).isEqualTo(newKey);
            return null;
        }).when(storage).delete(oldKey);

        service.upload(user.getId(), image());

        assertThat(saved().getProfileImageObjectKey()).isEqualTo(newKey);
        assertThat(saved().getNickname()).isEqualTo("초록");
        assertThat(saved().getProfileImageCode()).isEqualTo((short) 3);
        verify(storage).store(user.getId(), WEBP);
        verify(storage).delete(oldKey);
        assertThat(taskCount()).isZero();
    }

    @Test
    @DisplayName("개인 사진에서 기본 이미지로 바꿀 때도 DB 변경 후 이전 개인 파일을 정리한다")
    void changesToDefaultAndDeletesUploadedImage() {
        service.changeDefault(user.getId(), 7);

        assertThat(saved().getProfileImageObjectKey()).isNull();
        assertThat(saved().getProfileImageCode()).isEqualTo((short) 7);
        verify(storage).delete(oldKey);
        assertThat(taskCount()).isZero();
    }

    @Test
    @DisplayName("DB 저장 실패가 확정되면 기존 프로필과 삭제 기록을 롤백하고 새 파일만 정리한다")
    void rollsBackAndDeletesNewImageWhenDatabaseFails() {
        jdbc.execute("ALTER TABLE users ADD CONSTRAINT test_profile_image_failure "
                + "CHECK (profile_image_object_key IS DISTINCT FROM '" + newKey + "')");
        try {
            assertThatThrownBy(() -> service.upload(user.getId(), image()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(saved().getProfileImageObjectKey()).isEqualTo(oldKey);
            verify(storage).delete(newKey);
            verify(storage, never()).delete(oldKey);
            assertThat(taskCount()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT test_profile_image_failure");
        }
    }

    @Test
    @DisplayName("업로드 응답이 실패하면 기존 DB를 유지하고 새 경로를 기록해 다음 주기에 정리한다")
    void preservesCurrentImageWhenUploadFails() {
        ProfileImageStorageException failure = new ProfileImageStorageException("upload failed", newKey, null);
        when(storage.store(eq(user.getId()), any(byte[].class))).thenThrow(failure);

        assertThatThrownBy(() -> service.upload(user.getId(), image())).isSameAs(failure);

        assertThat(saved().getProfileImageObjectKey()).isEqualTo(oldKey);
        verify(storage, never()).delete(newKey);
        verify(storage, never()).delete(oldKey);
        assertThat(taskCount()).isEqualTo(1);
        jdbc.update("UPDATE profile_image_cleanup_tasks SET next_attempt_at = ?", java.sql.Timestamp.from(NOW));
        cleaner.retryPending();
        verify(storage).delete(newKey);
        assertThat(taskCount()).isZero();
    }

    @Test
    @DisplayName("이전 사진 삭제만 실패하면 새 프로필을 유지하고 DB에 남긴 삭제 작업을 재시도한다")
    void retriesFailedDeletionWithoutFailingUpload() {
        doThrow(new ProfileImageStorageException("delete failed", oldKey, null)).when(storage).delete(oldKey);

        service.upload(user.getId(), image());

        assertThat(saved().getProfileImageObjectKey()).isEqualTo(newKey);
        assertThat(taskCount()).isEqualTo(1);
        doNothing().when(storage).delete(oldKey);
        jdbc.update("UPDATE profile_image_cleanup_tasks SET next_attempt_at = ?", java.sql.Timestamp.from(NOW));

        cleaner.retryPending();

        assertThat(taskCount()).isZero();
        verify(storage, times(2)).delete(oldKey);
        assertThat(saved().getProfileImageObjectKey()).isEqualTo(newKey);
    }

    @Test
    @DisplayName("저장 응답이 불명확해도 DB가 이미 새 파일을 참조한다면 삭제하지 않는다")
    void preservesImageAlreadyReferencedByUser() {
        changes.changeUploaded(user.getId(), newKey);

        cleaner.clean(user.getId(), newKey);

        assertThat(saved().getProfileImageObjectKey()).isEqualTo(newKey);
        verify(storage, never()).delete(newKey);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profile_image_cleanup_tasks WHERE object_key = ?",
                Integer.class, newKey)).isZero();
    }

    @Test
    @DisplayName("저장 트랜잭션이 진행 중이면 잠금이 풀린 뒤 확인해 커밋된 새 파일을 보존한다")
    void waitsForPendingCommitBeforeDecidingDeletion() throws Exception {
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            var saving = executor.submit(() -> transaction.executeWithoutResult(status -> {
                User locked = users.findByIdForUpdate(user.getId()).orElseThrow();
                locked.changeUploadedProfileImage(newKey, NOW);
                users.flush();
                updated.countDown();
                await(commit);
            }));
            assertThat(updated.await(5, TimeUnit.SECONDS)).isTrue();
            var cleaning = executor.submit(() -> {
                cleanupStarted.countDown();
                cleaner.clean(user.getId(), newKey);
            });
            assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cleaning.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            verify(storage, never()).delete(newKey);
            commit.countDown();
            saving.get(5, TimeUnit.SECONDS);
            cleaning.get(5, TimeUnit.SECONDS);
            assertThat(saved().getProfileImageObjectKey()).isEqualTo(newKey);
            verify(storage, never()).delete(newKey);
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("탈퇴한 회원은 변환이나 업로드를 시작하지 않는다")
    void rejectsDeletedUserBeforeProcessing() {
        jdbc.update("UPDATE users SET deleted_at = ? WHERE id = ?", java.sql.Timestamp.from(NOW), user.getId());

        assertThatThrownBy(() -> service.upload(user.getId(), image())).isInstanceOf(InvalidCurrentUserException.class);
        verifyNoInteractions(processor, storage);
    }

    @Test
    @DisplayName("S3 연결이 비활성화된 환경은 기존 프로필을 유지하며 저장 불가를 안내한다")
    void rejectsUploadWhenStorageIsDisabled() {
        var provider = new StaticListableBeanFactory().getBeanProvider(S3ProfileImageStorage.class);
        ProfileImageUploadService disabledService = new ProfileImageUploadService(
                processor, changes, cleaner, provider
        );

        assertThatThrownBy(() -> disabledService.upload(user.getId(), image()))
                .isInstanceOf(ProfileImageStorageException.class);
        assertThat(saved().getProfileImageObjectKey()).isEqualTo(oldKey);
        verifyNoInteractions(storage);
    }

    private User saved() {
        return users.findById(user.getId()).orElseThrow();
    }

    private int taskCount() {
        return jdbc.queryForObject("SELECT count(*) FROM profile_image_cleanup_tasks", Integer.class);
    }

    private void cleanDatabase() {
        jdbc.update("DELETE FROM profile_image_cleanup_tasks");
        users.deleteAll();
    }

    private MockMultipartFile image() {
        return new MockMultipartFile("image", "profile.png", "image/png", new byte[] {1});
    }

    private static String key(UUID userId, String imageId) {
        return "harudle/profile-images/dev/" + userId + "/" + imageId + ".webp";
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트 트랜잭션 대기 시간이 초과됐습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
