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
import com.harudle.auth.domain.exception.NicknameAlreadyInUseException;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.ProfileImageCleanupRepository;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.auth.infrastructure.nickname.NicknameCandidateRepository;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        ProfileImageUploadService.class, ProfileImageCleanupService.class, ProfileImageCleanupRepository.class,
        ProfileChangeService.class, NicknameChangeService.class, NicknameCandidateRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProfileImageUploadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");
    private static final byte[] WEBP = {1, 2, 3};

    @Autowired
    private ProfileImageUploadService service;
    @Autowired
    private ProfileImageChangeService changes;
    @Autowired
    private ProfileChangeService profileChanges;
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
    @DisplayName("사진 DB 저장이 실패하면 닉네임과 후보 점유도 롤백하고 새 파일만 정리한다")
    void rollsBackAndDeletesNewImageWhenDatabaseFails() {
        jdbc.execute("ALTER TABLE users ADD CONSTRAINT test_profile_image_failure "
                + "CHECK (profile_image_object_key IS DISTINCT FROM '" + newKey + "')");
        try {
            assertThatThrownBy(() -> service.changeProfile(user.getId(), "행복한 고양이", null, image()))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(saved().getNickname()).isEqualTo("초록");
            assertThat(saved().getProfileImageObjectKey()).isEqualTo(oldKey);
            assertThat(jdbc.queryForObject(
                    "SELECT assigned_user_id FROM nickname_candidates WHERE nickname = '행복한 고양이'",
                    UUID.class)).isNull();
            verify(storage).delete(newKey);
            verify(storage, never()).delete(oldKey);
            assertThat(taskCount()).isZero();
        } finally {
            jdbc.execute("ALTER TABLE users DROP CONSTRAINT test_profile_image_failure");
        }
    }

    @Test
    @DisplayName("업로드 응답이 실패하면 기존 DB를 유지하고 유예 후 다음 프로필 변경에서 정리한다")
    void preservesCurrentImageWhenUploadFails() {
        ProfileImageStorageException failure = new ProfileImageStorageException("upload failed", newKey, null);
        when(storage.store(eq(user.getId()), any(byte[].class))).thenThrow(failure);

        assertThatThrownBy(() -> service.changeProfile(user.getId(), "새닉네임", null, image())).isSameAs(failure);

        assertThat(saved().getNickname()).isEqualTo("초록");
        assertThat(saved().getProfileImageObjectKey()).isEqualTo(oldKey);
        verify(storage, never()).delete(newKey);
        verify(storage, never()).delete(oldKey);
        assertThat(taskCount()).isEqualTo(1);
        cleaner.clean(user.getId(), newKey);
        verify(storage, never()).delete(newKey);
        assertThat(taskCount()).isEqualTo(1);

        jdbc.update("UPDATE profile_image_cleanup_tasks SET delete_after = ?", java.sql.Timestamp.from(NOW));
        service.changeDefault(user.getId(), 7);
        verify(storage).delete(newKey);
        assertThat(taskCount()).isZero();
    }

    @Test
    @DisplayName("이전 사진 삭제만 실패하면 새 프로필을 유지하고 DB에 남긴 삭제 작업을 재시도한다")
    void retriesFailedDeletionWithoutFailingUpload() {
        doThrow(new ProfileImageStorageException("delete failed", oldKey, null)).when(storage).delete(oldKey);

        service.changeProfile(user.getId(), "새닉네임", null, image());

        assertThat(saved().getNickname()).isEqualTo("새닉네임");
        assertThat(saved().getProfileImageObjectKey()).isEqualTo(newKey);
        assertThat(taskCount()).isEqualTo(1);
        doNothing().when(storage).delete(oldKey);
        service.changeDefault(user.getId(), 7);

        assertThat(taskCount()).isZero();
        verify(storage, times(2)).delete(oldKey);
        assertThat(saved().getProfileImageObjectKey()).isNull();
        assertThat(saved().getProfileImageCode()).isEqualTo((short) 7);
    }

    @Test
    @DisplayName("사진 변경은 해당 회원의 삭제 가능한 작업만 정리하고 기본 이미지끼리 바꿔도 남은 작업을 처리한다")
    void cleansOnlyEligibleTasksOfChangingUser() {
        UUID otherUserId = UUID.randomUUID();
        String otherKey = key(otherUserId, "33333333-3333-3333-3333-333333333333");
        String previousFailureKey = key(user.getId(), "44444444-4444-4444-4444-444444444444");
        String deferredKey = key(user.getId(), "55555555-5555-5555-5555-555555555555");
        jdbc.update("""
                INSERT INTO profile_image_cleanup_tasks (object_key, user_id, created_at, delete_after)
                VALUES (?, ?, ?, ?), (?, ?, ?, ?), (?, ?, ?, ?)
                """, otherKey, otherUserId, java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                previousFailureKey, user.getId(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW),
                deferredKey, user.getId(), java.sql.Timestamp.from(NOW),
                java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));

        service.changeDefault(user.getId(), 7);

        verify(storage).delete(oldKey);
        verify(storage).delete(previousFailureKey);
        verify(storage, never()).delete(otherKey);
        verify(storage, never()).delete(deferredKey);
        assertThat(taskCount()).isEqualTo(2);

        jdbc.update("UPDATE profile_image_cleanup_tasks SET delete_after = ? WHERE object_key = ?",
                java.sql.Timestamp.from(NOW), deferredKey);
        service.changeDefault(user.getId(), 8);

        verify(storage).delete(deferredKey);
        verify(storage, never()).delete(otherKey);
        assertThat(taskCount()).isEqualTo(1);
        assertThat(saved().getProfileImageCode()).isEqualTo((short) 8);
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
                processor, changes, profileChanges, cleaner, provider
        );

        assertThatThrownBy(() -> disabledService.upload(user.getId(), image()))
                .isInstanceOf(ProfileImageStorageException.class);
        assertThat(saved().getProfileImageObjectKey()).isEqualTo(oldKey);
        verifyNoInteractions(storage);
    }

    @Test
    @DisplayName("닉네임과 기본 이미지를 함께 확정한 뒤 이전 개인 사진을 삭제한다")
    void changesNicknameAndDefaultImageTogether() {
        doAnswer(invocation -> {
            assertThat(saved().getNickname()).isEqualTo("새닉네임");
            assertThat(saved().getProfileImageCode()).isEqualTo((short) 7);
            assertThat(saved().getProfileImageObjectKey()).isNull();
            return null;
        }).when(storage).delete(oldKey);

        service.changeProfile(user.getId(), "새닉네임", 7, null);

        verify(storage).delete(oldKey);
        verifyNoInteractions(processor);
        assertThat(taskCount()).isZero();
    }

    @Test
    @DisplayName("닉네임과 업로드 사진을 함께 확정한 뒤 이전 파일을 삭제한다")
    void changesNicknameAndUploadedImageTogether() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(saved().getNickname()).isEqualTo("새닉네임");
            assertThat(saved().getProfileImageObjectKey()).isEqualTo(newKey);
            return null;
        }).when(storage).delete(oldKey);

        service.changeProfile(user.getId(), "새닉네임", null, image());

        verify(storage).delete(oldKey);
        assertThat(saved().getProfileImageCode()).isEqualTo((short) 3);
        assertThat(taskCount()).isZero();
    }

    @Test
    @DisplayName("중복 닉네임이면 기존 프로필을 유지하고 이미 업로드한 새 사진만 정리한다")
    void deletesNewUploadWhenNicknameIsTaken() {
        User other = new User(null, "다른회원", NOW);
        other.assignInitialPublicProfile("사용중인닉네임", 1, NOW);
        users.save(other);

        assertThatThrownBy(() -> service.changeProfile(user.getId(), "사용중인닉네임", null, image()))
                .isInstanceOf(NicknameAlreadyInUseException.class);

        assertThat(saved().getNickname()).isEqualTo("초록");
        assertThat(saved().getProfileImageObjectKey()).isEqualTo(oldKey);
        verify(storage).delete(newKey);
        verify(storage, never()).delete(oldKey);
        assertThat(taskCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "앞뒤 ", "너무긴닉네임열한글자입니다", "금지!"})
    @DisplayName("닉네임 형식이 잘못되면 사진 변환과 업로드 전에 거절한다")
    void rejectsInvalidNicknameBeforeProcessing(String nickname) {
        assertThatThrownBy(() -> service.changeProfile(user.getId(), nickname, null, image()))
                .isInstanceOf(com.harudle.auth.domain.exception.InvalidNicknameException.class);
        verifyNoInteractions(processor, storage);
        assertThat(saved().getNickname()).isEqualTo("초록");
        assertThat(saved().getProfileImageObjectKey()).isEqualTo(oldKey);
        assertThat(taskCount()).isZero();
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
