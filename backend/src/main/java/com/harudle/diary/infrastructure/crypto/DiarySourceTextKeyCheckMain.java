package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.service.DiarySourceTextWritePolicyValidator;
import com.harudle.diary.service.DiarySourceTextWriteProperties;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Operates on key evidence without starting the application, database migrations, or generation adapters.
 * Ordinary verify/register remain database-independent; initialize and verify-data use dedicated JDBC connections.
 * Uses Boot's JSON/config-data processing and property binding, including profiles and imported settings.
 * Only fixed diagnostics reach stderr; configuration values and nested binding failures must remain private.
 */
public final class DiarySourceTextKeyCheckMain {

    private static final String PREFIX = "harudle.diary.source-text-encryption";

    private DiarySourceTextKeyCheckMain() {
    }

    public static void main(String[] args) {
        int status = execute(args, new StandardEnvironment());
        if (status != 0) {
            System.err.println("Diary encryption key operation failed.");
        }
        System.exit(status);
    }

    static int execute(String[] args, ConfigurableEnvironment environment) {
        try {
            requireCommand(args);
            prepareEnvironment(environment);
            DiarySourceTextEncryptionProperties properties = Binder.get(environment)
                    .bind(PREFIX, Bindable.of(DiarySourceTextEncryptionProperties.class))
                    .orElseThrow(DiarySourceTextCryptoException::new);
            Path file = Path.of(environment.getRequiredProperty(PREFIX + ".verification-file"));
            String deploymentEnvironment = environment.getRequiredProperty("DEPLOY_ENV");
            DiarySourceTextKeyVerification verification = new DiarySourceTextKeyVerification(properties);
            switch (args[0]) {
                case "verify", "verify-data" -> {
                    verifyWritePolicy(environment);
                    verification.verify(file, deploymentEnvironment);
                    if ("verify-data".equals(args[0])) {
                        return verifyStoredData(environment, properties);
                    }
                }
                case "initialize" -> initialize(environment, verification, file, deploymentEnvironment);
                case "register" -> verification.register(file, deploymentEnvironment);
                default -> throw new DiarySourceTextCryptoException();
            }
            return 0;
        } catch (RuntimeException | SQLException exception) {
            return 1;
        }
    }

    private static void initialize(ConfigurableEnvironment environment, DiarySourceTextKeyVerification verification,
            Path file, String deploymentEnvironment) throws SQLException {
        try (Connection connection = openConnection(environment)) {
            new DiarySourceTextKeyInitialization(verification).initialize(connection, file, deploymentEnvironment);
        }
    }

    private static int verifyStoredData(ConfigurableEnvironment environment,
            DiarySourceTextEncryptionProperties properties) throws SQLException {
        try (Connection connection = openConnection(environment)) {
            var result = new DiarySourceTextStoredDataVerification(new AesGcmDiarySourceTextCipher(properties))
                    .verify(connection);
            System.out.printf("{\"checked\":%d,\"failed\":%d}%n", result.checked(), result.failed());
            return result.failed() == 0 ? 0 : 1;
        }
    }

    private static Connection openConnection(ConfigurableEnvironment environment) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", environment.getRequiredProperty("spring.datasource.username"));
        properties.setProperty("password", environment.getRequiredProperty("spring.datasource.password"));
        properties.setProperty("ApplicationName", "harudle-diary-key-maintenance");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        return DriverManager.getConnection(environment.getRequiredProperty("spring.datasource.url"), properties);
    }

    private static void verifyWritePolicy(ConfigurableEnvironment environment) {
        DiarySourceTextWriteProperties policy = Binder.get(environment)
                .bind(PREFIX, Bindable.of(DiarySourceTextWriteProperties.class))
                .orElseThrow(DiarySourceTextCryptoException::new);
        DiarySourceTextWritePolicyValidator.validate(policy, environment);
    }

    private static void requireCommand(String[] args) {
        if (args.length != 1 || !("verify".equals(args[0]) || "verify-data".equals(args[0])
                || "initialize".equals(args[0])
                || "register".equals(args[0]))) {
            throw new DiarySourceTextCryptoException();
        }
    }

    private static void prepareEnvironment(ConfigurableEnvironment environment) {
        new SpringApplicationJsonEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(DiarySourceTextKeyCheckMain.class));
        ConfigDataEnvironmentPostProcessor.applyTo(environment);
    }
}
