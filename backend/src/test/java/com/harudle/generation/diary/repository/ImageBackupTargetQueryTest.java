package com.harudle.generation.diary.repository;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.harudle.generation.diary.domain.DiaryGeneration;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class ImageBackupTargetQueryTest {
    @Test
    void hibernateAcceptsRecordProjectionAndTimestampUuidCursorWithoutDatabase() {
        try (var factory = new Configuration().addAnnotatedClass(DiaryGeneration.class)
                .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .setProperty("hibernate.boot.allow_jdbc_metadata_access", "false")
                .setProperty("hibernate.connection.provider_class",
                        "org.hibernate.engine.jdbc.connections.internal.UserSuppliedConnectionProviderImpl")
                .buildSessionFactory(); var session = factory.openSession()) {
            for (var method : DiaryGenerationQueryRepository.class.getMethods()) {
                if (method.getName().startsWith("findImageBackupTargets")) {
                    String jpql = method.getAnnotation(Query.class).value();
                    assertThatCode(() -> session.createSelectionQuery(jpql, ImageBackupTarget.class))
                            .doesNotThrowAnyException();
                }
            }
        }
    }
}
