package com.harudle.common.persistence;

import jakarta.persistence.EntityManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 요청의 잠금 대기를 제한하며, 연결에 이미 적용된 더 짧은 제한은 유지한다. */
@Component
@EnableConfigurationProperties(DatabaseLockProperties.class)
public class PostgresTransactionLockTimeout {
    private static final String APPLY = """
            SELECT set_config('lock_timeout',
                CAST(CASE WHEN CAST(setting AS bigint) > 0
                    THEN LEAST(CAST(setting AS bigint), :milliseconds)
                    ELSE :milliseconds END AS text), true)
            FROM pg_settings WHERE name = 'lock_timeout'
            """;
    private final EntityManager entityManager;
    private final DatabaseLockProperties properties;

    public PostgresTransactionLockTimeout(EntityManager entityManager, DatabaseLockProperties properties) {
        this.entityManager = entityManager;
        this.properties = properties;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void apply() {
        entityManager.createNativeQuery(APPLY)
                .setParameter("milliseconds", properties.interactionLockTimeout().toMillis())
                .getSingleResult();
    }
}
