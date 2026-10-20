package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.service.DiarySourceTextWritePolicyValidator;
import com.harudle.diary.service.DiarySourceTextWriteProperties;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Operates on key evidence without starting the application, database migrations, or generation adapters.
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
                case "verify" -> {
                    verifyWritePolicy(environment);
                    verification.verify(file, deploymentEnvironment);
                }
                case "initialize" -> verification.initialize(file, deploymentEnvironment);
                case "register" -> verification.register(file, deploymentEnvironment);
                default -> throw new DiarySourceTextCryptoException();
            }
            return 0;
        } catch (RuntimeException exception) {
            return 1;
        }
    }

    private static void verifyWritePolicy(ConfigurableEnvironment environment) {
        DiarySourceTextWriteProperties policy = Binder.get(environment)
                .bind(PREFIX, Bindable.of(DiarySourceTextWriteProperties.class))
                .orElseThrow(DiarySourceTextCryptoException::new);
        DiarySourceTextWritePolicyValidator.validate(policy, environment);
    }

    private static void requireCommand(String[] args) {
        if (args.length != 1 || !("verify".equals(args[0]) || "initialize".equals(args[0])
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
