package com.harudle.recovery;

import com.harudle.admin.presentation.AdminImageRecoveryController;
import com.harudle.admin.presentation.AdminR2ImageRecoveryController;
import com.harudle.admin.service.AdminImageRecoveryService;
import com.harudle.admin.service.AdminR2ImageRecoveryService;
import com.harudle.admin.service.RecoveryExecutionGate;
import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.infrastructure.UserRepository;
import com.harudle.common.config.TimeConfiguration;
import com.harudle.common.error.ImageRecoveryErrorConfiguration;
import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.common.security.ApiCorsConfiguration;
import com.harudle.common.security.CsrfConfiguration;
import com.harudle.common.security.JwtTokenConfiguration;
import com.harudle.common.security.LegacyCsrfCookieCleaner;
import com.harudle.generation.config.ImageRecoveryConfiguration;
import com.harudle.generation.config.ImageStorageConfiguration;
import com.harudle.generation.config.R2StorageConfiguration;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.diary.domain.DiarySourceTextCipher;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import com.harudle.moderation.infrastructure.JpaModerationRepository;
import com.harudle.legal.infrastructure.ImageRecoveryTermsConfiguration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceInitializationAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.security.oauth2.client.autoconfigure.OAuth2ClientAutoConfiguration;
import org.springframework.boot.security.oauth2.client.autoconfigure.servlet.OAuth2ClientWebSecurityAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** Explicit maintenance entry point. Deliberately has no component scan or component stereotype. */
@EnableAutoConfiguration(exclude = {FlywayAutoConfiguration.class, DataSourceInitializationAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class, OAuth2ClientWebSecurityAutoConfiguration.class})
@EntityScan(basePackages = "com.harudle")
@EnableJpaRepositories(basePackageClasses = {UserRepository.class, DiaryGenerationRepository.class,
        GenerationPromptRepository.class})
@Import({AdminImageRecoveryController.class, AdminR2ImageRecoveryController.class,
        AdminImageRecoveryService.class, AdminR2ImageRecoveryService.class, RecoveryExecutionGate.class,
        ImageStorageConfiguration.class, R2StorageConfiguration.class, ImageRecoveryConfiguration.class,
        RecoveryImageGenerationConfiguration.class, ImageRecoverySecurityConfiguration.class,
        ImageRecoveryAuthenticationConfiguration.class, ImageRecoveryTermsConfiguration.class,
        ImageRecoveryCsrfController.class, JwtTokenConfiguration.class, CsrfConfiguration.class, LegacyCsrfCookieCleaner.class,
        ApiCorsConfiguration.class, ImageRecoveryErrorConfiguration.class,
        ExternalApiLogger.class, TimeConfiguration.class, AccountAccessPolicy.class, JpaModerationRepository.class})
public class ImageRecoveryApplication {
    public static void main(String[] args) {
        var application = new SpringApplication(ImageRecoveryApplication.class);
        application.setAdditionalProfiles("image-recovery");
        application.run(args);
    }

    @Bean
    static BeanFactoryPostProcessor recoverySchemaPolicy(Environment environment) {
        return beans -> {
            if (!"validate".equals(environment.getProperty("spring.jpa.hibernate.ddl-auto"))
                    || environment.getProperty("spring.jpa.generate-ddl", Boolean.class, false)
                    || !"validate".equals(environment.getProperty("spring.jpa.properties.hibernate.hbm2ddl.auto", "validate"))
                    || !"none".equals(environment.getProperty(
                            "spring.jpa.properties.jakarta.persistence.schema-generation.database.action", "none"))
                    || !"none".equals(environment.getProperty(
                            "spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action", "none"))) {
                throw new IllegalStateException("Image recovery requires schema validation without schema changes.");
            }
            for (Class<?> forbidden : new Class<?>[]{DiarySourceTextCipher.class, DiaryGenerationWorker.class,
                    ScheduledAnnotationBeanPostProcessor.class, ApplicationRunner.class, CommandLineRunner.class,
                    com.harudle.HarudleApplication.class}) {
                if (beans.getBeanNamesForType(forbidden, false, false).length != 0) {
                    throw new IllegalStateException("Image recovery cannot load normal application processing.");
                }
            }
        };
    }
}
