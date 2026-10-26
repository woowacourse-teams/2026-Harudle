package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration.MigrationResult;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration.Status;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration.VerificationResult;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigrationException;
import com.harudle.diary.service.DiarySourceTextWritePolicyValidator;
import com.harudle.diary.service.DiarySourceTextWriteProperties;
import com.harudle.generation.diary.service.RequestFingerprintGenerator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

/**
 * Explicit JDBC maintenance entry point; never starts the web application, Flyway, or AI clients.
 * Emits only an allowlisted command with aggregate counts or a fixed error code.
 * Exceptions never expose input or database diagnostics.
 */
public final class DiarySourceTextBackfillMain {

    private static final String PREFIX = "harudle.diary.source-text-encryption";
    private static final Set<String> COMMANDS = Set.of("status", "migrate", "verify", "finalize", "ready");

    private DiarySourceTextBackfillMain() {
    }

    public static void main(String[] args) {
        int status = execute(args, new StandardEnvironment());
        if (status == 1) {
            System.err.println("Diary source text migration failed.");
        }
        System.exit(status);
    }

    static int execute(String[] args, ConfigurableEnvironment environment) {
        String command = allowedCommand(args);
        FailurePhase phase = FailurePhase.CONFIGURATION;
        try {
            Options options = parse(args);
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
            CommandResult result;
            try (Connection connection = DriverManager.getConnection(databaseUrl, databaseProperties)) {
                phase = FailurePhase.DATABASE_OPERATION;
                DiarySourceTextMigration migration = new DiarySourceTextMigration(
                        connection, new AesGcmDiarySourceTextCipher(keys), new RequestFingerprintGenerator()
                );
                result = run(options, migration);
            }
            System.out.println(result.output());
            return result.status();
        } catch (RuntimeException | SQLException | Error exception) {
            printFailure(command, errorCode(phase, exception));
            return 1;
        }
    }

    private static Properties connectionProperties(ConfigurableEnvironment environment) {
        Properties properties = new Properties();
        properties.setProperty("user", environment.getRequiredProperty("spring.datasource.username"));
        properties.setProperty("password", environment.getRequiredProperty("spring.datasource.password"));
        properties.setProperty("ApplicationName", "harudle-diary-source-backfill");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        return properties;
    }

    private static CommandResult run(Options options, DiarySourceTextMigration migration) {
        return switch (options.command()) {
            case "status" -> new CommandResult(0, summary(options.command(), "", migration.status()));
            case "ready" -> {
                migration.requireReady();
                yield new CommandResult(0, summary(options.command(), "", migration.status()));
            }
            case "migrate" -> migrate(options, migration);
            case "verify", "finalize" -> verify(options, migration);
            default -> throw new IllegalArgumentException("Unsupported maintenance command.");
        };
    }

    private static CommandResult migrate(Options options, DiarySourceTextMigration migration) {
        MigrationResult result = migration.migrate(options.batchSize(), options.maxBatches());
        String counts = "\"attempted\":%d,\"migrated\":%d,\"skippedProcessing\":%d,\"failed\":%d,"
                + "\"orphanMigrated\":%d,";
        String output = summary(options.command(), counts.formatted(result.attempted(), result.migrated(),
                result.skippedProcessing(), result.failed(), result.orphanMigrated()), result.remaining());
        if (result.failed() > 0 || result.remaining().unsupportedRows() > 0) {
            return new CommandResult(1, output);
        }
        return new CommandResult(result.remaining().legacyRows() > 0 ? 2 : 0, output);
    }

    private static CommandResult verify(Options options, DiarySourceTextMigration migration) {
        VerificationResult result = "finalize".equals(options.command())
                ? migration.finalizeMigration(options.batchSize()) : migration.verify(options.batchSize());
        String output = summary(options.command(),
                "\"checked\":%d,\"failed\":%d,".formatted(result.checked(), result.failed()),
                result.remaining());
        int status = result.failed() > 0 || result.remaining().legacyRows() > 0
                || result.remaining().unsupportedRows() > 0
                || ("finalize".equals(options.command()) && !result.remaining().finalized()) ? 1 : 0;
        return new CommandResult(status, output);
    }

    private static String summary(String command, String counts, Status status) {
        String summary = "{\"command\":\"%s\",%s\"legacyRows\":%d,\"encryptedRows\":%d,"
                + "\"unsupportedRows\":%d,\"processingLegacyRows\":%d,\"orphanRows\":%d,\"finalized\":%s}";
        return summary.formatted(command, counts, status.legacyRows(), status.encryptedRows(),
                status.unsupportedRows(), status.processingLegacyRows(), status.orphanRows(), status.finalized());
    }

    private static ErrorCode errorCode(FailurePhase phase, Throwable failure) {
        if (failure instanceof Error) {
            return ErrorCode.UNEXPECTED_FAILURE;
        }
        if (phase == FailurePhase.DATABASE_OPERATION && failure instanceof DiarySourceTextMigrationException migration
                && migration.reason() == DiarySourceTextMigrationException.Reason.ISOLATION_LEVEL_UNSUPPORTED) {
            return ErrorCode.ISOLATION_LEVEL_UNSUPPORTED;
        }
        return phase.errorCode;
    }

    private static void printFailure(String command, ErrorCode code) {
        System.out.printf("{\"command\":\"%s\",\"errorCode\":\"%s\"}%n", command, code.name());
    }

    private static String allowedCommand(String[] args) {
        return args != null && args.length > 0 && args[0] != null && COMMANDS.contains(args[0]) ? args[0] : "invalid";
    }

    static Options parse(String[] args) {
        if ("invalid".equals(allowedCommand(args))) {
            throw new IllegalArgumentException("Unsupported maintenance command.");
        }
        int batchSize = 100;
        int maxBatches = 1;
        Set<String> seen = new HashSet<>();
        for (int index = 1; index < args.length; index += 2) {
            if (index + 1 >= args.length || !seen.add(args[index])) {
                throw new IllegalArgumentException("Invalid maintenance options.");
            }
            switch (args[index]) {
                case "--batch-size" -> batchSize = positiveInteger(args[index + 1], 1_000);
                case "--max-batches" -> maxBatches = positiveInteger(args[index + 1], 10_000);
                default -> throw new IllegalArgumentException("Invalid maintenance options.");
            }
        }
        validateOptions(args[0], seen);
        return new Options(args[0], batchSize, maxBatches);
    }

    private static void validateOptions(String command, Set<String> seen) {
        if ((Set.of("status", "ready").contains(command) && !seen.isEmpty())
                || (!"migrate".equals(command) && seen.contains("--max-batches"))) {
            throw new IllegalArgumentException("Invalid maintenance options.");
        }
    }

    private static int positiveInteger(String value, int maximum) {
        if (!value.matches("[1-9][0-9]{0,4}")) {
            throw new IllegalArgumentException("Invalid maintenance limit.");
        }
        int parsed = Integer.parseInt(value);
        if (parsed > maximum) {
            throw new IllegalArgumentException("Invalid maintenance limit.");
        }
        return parsed;
    }

    record Options(String command, int batchSize, int maxBatches) {
    }

    private record CommandResult(int status, String output) {
    }

    private enum ErrorCode {
        CONFIGURATION_INVALID,
        KEY_VERIFICATION_FAILED,
        DATABASE_CONNECTION_FAILED,
        DATABASE_OPERATION_FAILED,
        ISOLATION_LEVEL_UNSUPPORTED,
        UNEXPECTED_FAILURE
    }

    private enum FailurePhase {
        CONFIGURATION(ErrorCode.CONFIGURATION_INVALID),
        KEY_VERIFICATION(ErrorCode.KEY_VERIFICATION_FAILED),
        DATABASE_CONNECTION(ErrorCode.DATABASE_CONNECTION_FAILED),
        DATABASE_OPERATION(ErrorCode.DATABASE_OPERATION_FAILED);

        private final ErrorCode errorCode;

        FailurePhase(ErrorCode errorCode) {
            this.errorCode = errorCode;
        }
    }
}
