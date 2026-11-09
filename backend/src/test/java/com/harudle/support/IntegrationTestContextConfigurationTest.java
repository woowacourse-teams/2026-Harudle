package com.harudle.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;

class IntegrationTestContextConfigurationTest {

    @Test
    void categoryServicesShareContextCacheKey() throws ClassNotFoundException {
        assertSameContext(
                "com.harudle.category.service.CategoryCreateServiceTest",
                "com.harudle.category.service.DefaultCategoryReaderTest"
        );
    }

    @Test
    void controllersShareContextCacheKey() throws ClassNotFoundException {
        assertSameContext(
                "com.harudle.admin.presentation.AdminGenerationControllerTest",
                "com.harudle.admin.presentation.AdminUserControllerTest",
                "com.harudle.auth.presentation.AuthControllerTest",
                "com.harudle.auth.presentation.CurrentUserControllerTest",
                "com.harudle.category.presentation.CategoryControllerTest",
                "com.harudle.guest.presentation.GuestSessionControllerTest",
                "com.harudle.push.presentation.PushRegistrationControllerTest"
        );
    }

    @Test
    void servicesWithCommonJwtMockShareContextCacheKey() throws ClassNotFoundException {
        assertSameContext(
                "com.harudle.HarudleApplicationTests",
                "com.harudle.auth.application.RefreshTokenCleanupPersistenceTest",
                "com.harudle.admin.infrastructure.JpaAdminGenerationHistoryQueryRepositoryTest",
                "com.harudle.admin.service.AdminGenerationUsageServiceTest",
                "com.harudle.diary.service.DiaryDeletionPersistenceTest",
                "com.harudle.diary.service.DiaryQueryPersistenceTest",
                "com.harudle.diary.service.DiarySourceTextEncryptionPersistenceTest",
                "com.harudle.diary.service.EncryptedGuestDiaryClaimPersistenceTest",
                "com.harudle.feed.service.FeedWithoutPushRegistrationPersistenceTest",
                "com.harudle.generation.diary.service.DiaryGenerationFailurePersistenceTest",
                "com.harudle.generation.prompt.infrastructure.GenerationPromptBootstrapPersistenceTest",
                "com.harudle.generation.usage.infrastructure.JpaGenerationUsageRepositoryTest",
                "com.harudle.guest.repository.GuestSessionConcurrencyTest",
                "com.harudle.guest.repository.GuestSessionRepositoryTest",
                "com.harudle.push.infrastructure.JdbcPushDeliveryQueueTest",
                "com.harudle.push.service.PushRegistrationServiceTest",
                "com.harudle.share.service.ShareLinkQueryServiceTest",
                "com.harudle.share.service.ShareLinkServiceTest"
        );
    }

    @Test
    void feedInteractionPersistenceTestsShareContextCacheKey() throws ClassNotFoundException {
        assertSameContext(
                "com.harudle.comment.infrastructure.CommentPersistenceTest",
                "com.harudle.feed.infrastructure.FeedLikePersistenceTest",
                "com.harudle.notification.infrastructure.NotificationPersistenceTest"
        );
    }

    @Test
    void authJpaTestsShareContextCacheKey() throws ClassNotFoundException {
        assertSameContext(
                "com.harudle.auth.application.CurrentUserServiceTest",
                "com.harudle.auth.application.NicknameChangeServiceTest",
                "com.harudle.auth.application.OAuthLoginServiceTest",
                "com.harudle.auth.application.ProfileImageUploadServiceTest",
                "com.harudle.auth.application.RefreshTokenServiceTest",
                "com.harudle.auth.infrastructure.UserRepositoryTest",
                "com.harudle.profile.service.DefaultPublicProfileReaderTest"
        );
    }

    @Test
    void feedPersistenceTestsShareContextCacheKey() throws ClassNotFoundException {
        assertSameContext(
                "com.harudle.feed.service.FeedDeletionPersistenceTest",
                "com.harudle.feed.service.FeedListAndInteractionPersistenceTest",
                "com.harudle.feed.service.FeedPublicationPersistenceTest"
        );
    }

    private void assertSameContext(String... classNames) throws ClassNotFoundException {
        MergedContextConfiguration expected = contextConfiguration(classNames[0]);
        for (String className : classNames) {
            assertThat(contextConfiguration(className))
                    .as("context cache key for %s", className)
                    .isEqualTo(expected);
        }
    }

    private MergedContextConfiguration contextConfiguration(String className) throws ClassNotFoundException {
        Class<?> testClass = Class.forName(className, false, getClass().getClassLoader());
        return BootstrapUtils.resolveTestContextBootstrapper(testClass).buildMergedContextConfiguration();
    }
}
