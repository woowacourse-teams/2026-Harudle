package com.harudle.diary.infrastructure.crypto;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiarySourceTextKeyInitializationResourcesTest {

    private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @TempDir
    private Path directory;

    private final DiarySourceTextKeyVerification verification = new DiarySourceTextKeyVerification(
            new DiarySourceTextEncryptionProperties("primary", Map.of("primary", KEY))
    );

    @Test
    @DisplayName("클래스패스에 같은 버전 SQL이 둘이면 DB 검사 전에 초기화를 거절한다")
    void rejectDuplicateBundledVersions() throws Throwable {
        Path migrations = Files.createDirectories(directory.resolve("db/migration"));
        Files.writeString(migrations.resolve("V1__duplicate_initialization_fixture.sql"), "-- Public duplicate fixture");
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader duplicate = new URLClassLoader(new URL[]{directory.toUri().toURL()}, original)) {
            withClassLoader(duplicate, this::assertResourceFailure);
        }
    }

    @Test
    @DisplayName("클래스패스에 마이그레이션이 없으면 빈 DB로 추측하지 않고 초기화를 거절한다")
    void rejectMissingBundledMigrations() throws Throwable {
        withClassLoader(new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) {
                return Collections.emptyEnumeration();
            }
        }, this::assertResourceFailure);
    }

    @Test
    @DisplayName("클래스패스 탐색 장애는 원인과 경로를 노출하지 않고 초기화를 거절한다")
    void rejectMigrationResourceScanFailure() throws Throwable {
        withClassLoader(new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("private-resource-scan-path");
            }
        }, this::assertResourceFailure);
    }

    @Test
    @DisplayName("암호화 번호의 SQL 이름이 다른 기능이면 암호화 적용 여부의 근거로 삼지 않는다")
    void rejectEncryptionVersionWhoseScriptNameDoesNotMatch() throws Throwable {
        Path migrations = Files.createDirectories(directory.resolve("db/migration"));
        Files.writeString(migrations.resolve("V1__public_initialization_fixture.sql"), "-- Public initial fixture");
        Files.writeString(migrations.resolve("V38__unrelated_initialization_fixture.sql"), "-- Public unrelated fixture");
        try (URLClassLoader unrelated = new URLClassLoader(new URL[]{directory.toUri().toURL()}, null)) {
            withClassLoader(unrelated, this::assertResourceFailure);
        }
    }

    private void assertResourceFailure() {
        assertThatThrownBy(() -> new DiarySourceTextKeyInitialization(verification))
                .isExactlyInstanceOf(DiarySourceTextCryptoException.class)
                .hasMessage("일기 원문 암호화 처리에 실패했습니다.").hasNoCause();
    }

    private void withClassLoader(ClassLoader loader, ThrowingCallable operation) throws Throwable {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            operation.call();
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }
}
