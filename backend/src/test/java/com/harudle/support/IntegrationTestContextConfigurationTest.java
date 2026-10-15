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
                "com.harudle.auth.presentation.CurrentUserControllerTest",
                "com.harudle.category.presentation.CategoryControllerTest",
                "com.harudle.guest.presentation.GuestSessionControllerTest"
        );
    }

    @Test
    void servicesWithCommonJwtMockShareContextCacheKey() throws ClassNotFoundException {
        assertSameContext(
                "com.harudle.HarudleApplicationTests",
                "com.harudle.admin.infrastructure.JpaAdminGenerationHistoryQueryRepositoryTest",
                "com.harudle.admin.service.AdminGenerationUsageServiceTest",
                "com.harudle.diary.service.DiaryDeletionPersistenceTest",
                "com.harudle.diary.service.DiaryQueryPersistenceTest",
                "com.harudle.feed.service.FeedWithoutPushRegistrationPersistenceTest",
                "com.harudle.generation.diary.service.DiaryGenerationFailurePersistenceTest",
                "com.harudle.generation.prompt.infrastructure.GenerationPromptBootstrapPersistenceTest",
                "com.harudle.generation.usage.infrastructure.JpaGenerationUsageRepositoryTest",
                "com.harudle.guest.repository.GuestSessionConcurrencyTest",
                "com.harudle.guest.repository.GuestSessionRepositoryTest",
                "com.harudle.share.service.ShareLinkQueryServiceTest",
                "com.harudle.share.service.ShareLinkServiceTest"
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
