package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.infrastructure.migration.DiarySourceTextReadiness;
import com.harudle.diary.service.DiarySourceTextWritePolicyValidator;
import com.harudle.diary.service.DiarySourceTextWriteProperties;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/** Read-only deployment entry point with an exact success result and fixed, private failure codes. */
public final class DiarySourceTextReadinessMain {

    private static final String PREFIX = "harudle.diary.source-text-encryption";

    private DiarySourceTextReadinessMain() {
    }

    public static void main(String[] args) {
        int status = execute(args, new StandardEnvironment());
        if (status != 0) {
            System.err.println("Diary source text readiness check failed.");
        }
        System.exit(status);
    }

    static int execute(String[] args, ConfigurableEnvironment environment) {
        FailurePhase phase = FailurePhase.CONFIGURATION;
        try {
            if (args == null || args.length != 1 || !"ready".equals(args[0])) {
                throw new IllegalArgumentException("Unsupported readiness command.");
            }
            DiarySourceTextKeyCheckMain.prepareEnvironment(environment);
            DiarySourceTextEncryptionProperties keys = Binder.get(environment)
                    .bind(PREFIX, Bindable.of(DiarySourceTextEncryptionProperties.class))
                    .orElseThrow(DiarySourceTextCryptoException::new);
            DiarySourceTextWriteProperties policy = Binder.get(environment)
                    .bind(PREFIX, Bindable.of(DiarySourceTextWriteProperties.class))
                    .orElseThrow(DiarySourceTextCryptoException::new);
            DiarySourceTextWritePolicyValidator.validate(policy, environment);
            String databaseUrl = environment.getRequiredProperty("spring.datasource.url");
            Properties databaseProperties = connectionProperties(environment);
            phase = FailurePhase.KEY_VERIFICATION;
            new DiarySourceTextKeyVerification(keys).verify(
                    Path.of(environment.getRequiredProperty(PREFIX + ".verification-file")),
                    environment.getRequiredProperty("DEPLOY_ENV")
            );
            phase = FailurePhase.DATABASE_CONNECTION;
            try (Connection connection = DriverManager.getConnection(databaseUrl, databaseProperties)) {
                phase = FailurePhase.DATABASE_OPERATION;
                new DiarySourceTextReadiness(connection).requireReady();
            }
            System.out.println("{\"command\":\"ready\",\"ready\":true}");
            return 0;
        } catch (RuntimeException | SQLException | Error exception) {
            String code = exception instanceof Error ? "UNEXPECTED_FAILURE" : phase.errorCode;
            System.out.printf("{\"command\":\"ready\",\"errorCode\":\"%s\"}%n", code);
            return 1;
        }
    }

    private static Properties connectionProperties(ConfigurableEnvironment environment) {
        Properties properties = new Properties();
        properties.setProperty("user", environment.getRequiredProperty("spring.datasource.username"));
        properties.setProperty("password", environment.getRequiredProperty("spring.datasource.password"));
        properties.setProperty("ApplicationName", "harudle-diary-source-readiness");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        return properties;
    }

    private enum FailurePhase {
        CONFIGURATION("CONFIGURATION_INVALID"),
        KEY_VERIFICATION("KEY_VERIFICATION_FAILED"),
        DATABASE_CONNECTION("DATABASE_CONNECTION_FAILED"),
        DATABASE_OPERATION("DATABASE_OPERATION_FAILED");

        private final String errorCode;

        FailurePhase(String errorCode) {
            this.errorCode = errorCode;
        }
    }
}
