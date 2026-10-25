package com.harudle.diary.infrastructure.crypto;

import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration.MigrationResult;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration.Status;
import com.harudle.diary.infrastructure.migration.DiarySourceTextMigration.VerificationResult;
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
 * Emits only an allowlisted command and aggregate counts. Exceptions never expose input or database diagnostics.
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
            new DiarySourceTextKeyVerification(keys).verify(
                    Path.of(environment.getRequiredProperty(PREFIX + ".verification-file")),
                    environment.getRequiredProperty("DEPLOY_ENV")
            );
            try (Connection connection = openConnection(environment)) {
                DiarySourceTextMigration migration = new DiarySourceTextMigration(
                        connection, new AesGcmDiarySourceTextCipher(keys), new RequestFingerprintGenerator()
                );
                return run(options, migration);
            }
        } catch (RuntimeException | SQLException | Error exception) {
            return 1;
        }
    }

    private static Connection openConnection(ConfigurableEnvironment environment) throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", environment.getRequiredProperty("spring.datasource.username"));
        properties.setProperty("password", environment.getRequiredProperty("spring.datasource.password"));
        properties.setProperty("ApplicationName", "harudle-diary-source-backfill");
        properties.setProperty("connectTimeout", "10");
        properties.setProperty("socketTimeout", "30");
        return DriverManager.getConnection(environment.getRequiredProperty("spring.datasource.url"), properties);
    }

    private static int run(Options options, DiarySourceTextMigration migration) {
        return switch (options.command()) {
            case "status" -> {
                print(options.command(), "", migration.status());
                yield 0;
            }
            case "ready" -> {
                migration.requireReady();
                print(options.command(), "", migration.status());
                yield 0;
            }
            case "migrate" -> migrate(options, migration);
            case "verify", "finalize" -> verify(options, migration);
            default -> throw new IllegalArgumentException("Unsupported maintenance command.");
        };
    }

    private static int migrate(Options options, DiarySourceTextMigration migration) {
        MigrationResult result = migration.migrate(options.batchSize(), options.maxBatches());
        String counts = "\"attempted\":%d,\"migrated\":%d,\"skippedProcessing\":%d,\"failed\":%d,"
                + "\"orphanMigrated\":%d,";
        print(options.command(), counts.formatted(result.attempted(), result.migrated(),
                result.skippedProcessing(), result.failed(), result.orphanMigrated()), result.remaining());
        if (result.failed() > 0 || result.remaining().unsupportedRows() > 0) {
            return 1;
        }
        return result.remaining().legacyRows() > 0 ? 2 : 0;
    }

    private static int verify(Options options, DiarySourceTextMigration migration) {
        VerificationResult result = "finalize".equals(options.command())
                ? migration.finalizeMigration(options.batchSize()) : migration.verify(options.batchSize());
        print(options.command(), "\"checked\":%d,\"failed\":%d,".formatted(result.checked(), result.failed()),
                result.remaining());
        return result.failed() > 0 || result.remaining().legacyRows() > 0
                || result.remaining().unsupportedRows() > 0
                || ("finalize".equals(options.command()) && !result.remaining().finalized()) ? 1 : 0;
    }

    private static void print(String command, String counts, Status status) {
        String summary = "{\"command\":\"%s\",%s\"legacyRows\":%d,\"encryptedRows\":%d,"
                + "\"unsupportedRows\":%d,\"processingLegacyRows\":%d,\"orphanRows\":%d,\"finalized\":%s}";
        System.out.println(summary.formatted(command, counts, status.legacyRows(), status.encryptedRows(),
                status.unsupportedRows(), status.processingLegacyRows(), status.orphanRows(), status.finalized()));
    }

    static Options parse(String[] args) {
        if (args.length == 0 || !COMMANDS.contains(args[0])) {
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
}
