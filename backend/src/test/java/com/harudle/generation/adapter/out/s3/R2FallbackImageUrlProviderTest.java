package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.config.R2StorageProperties;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageLookupBudget;
import com.harudle.generation.diary.service.port.ImageLookupBudgetExceededException;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.ImageStorageException.DiagnosticType;
import com.harudle.generation.diary.service.port.ImageUrlProvider;
import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

@ExtendWith(OutputCaptureExtension.class)
class R2FallbackImageUrlProviderTest {
    private static final String ROOT = "harudle/generated/diary-images/dev/550e8400-e29b-41d4-a716-446655440000/";
    private static final String ORIGINAL = ROOT + "image.png";
    private static final String DETAIL = ROOT + "image-960.webp";
    private static final String THUMBNAIL = ROOT + "image-240.webp";
    private static final ImageAccessUrl S3_URL = new ImageAccessUrl(URI.create("https://example.s3.amazonaws.com/image"),
            Instant.parse("2099-01-01T00:00:00Z"));
    private static final ImageAccessUrl R2_URL = new ImageAccessUrl(URI.create("https://example.r2.cloudflarestorage.com/image"),
            Instant.parse("2099-01-01T00:00:00Z"));
    private final ImageUrlProvider primary = mock(ImageUrlProvider.class);
    private final ImageStorage storage = mock(ImageStorage.class);
    private final BackupObjectStorage backup = mock(BackupObjectStorage.class);
    private R2FallbackImageUrlProvider provider;

    @BeforeEach
    void setUp() {
        provider = new R2FallbackImageUrlProvider(primary, storage, backup, s3("dev"), r2("dev"), () -> 0);
    }

    @Test
    void slowSingleS3LookupSkipsR2AndSignsS3WithoutRepeatingHead(CapturedOutput output) {
        AtomicLong time = useFakeTime();
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            ImageLookupBudget budget = invocation.getArgument(1);
            assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(2));
            time.addAndGet(Duration.ofSeconds(20).toNanos());
            throw new ImageStorageException("timeout", null, DiagnosticType.CLIENT_ERROR);
        });
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);

        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(S3_URL);

        verify(storage).exists(eq(DETAIL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(storage);
        verifyNoInteractions(backup);
        verify(primary).createAccessUrl(DETAIL);
        verifyNoMoreInteractions(primary);
        assertThat(output.getOut()).contains("r2Result=BUDGET_EXHAUSTED", "result=S3_FALLBACK");
    }

    @Test
    void singleLookupSharesRemainingBudgetAcrossS3AndR2Candidates() {
        AtomicLong time = useFakeTime();
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            time.addAndGet(Duration.ofMillis(500).toNanos());
            return false;
        });
        when(backup.findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            ImageLookupBudget budget = invocation.getArgument(1);
            assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofMillis(1500));
            time.addAndGet(Duration.ofMillis(500).toNanos());
            return Optional.empty();
        });
        String jpegKey = ROOT + "image.jpg";
        when(backup.findMetadata(eq(jpegKey), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            ImageLookupBudget budget = invocation.getArgument(1);
            assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(1));
            return Optional.of(new BackupObjectMetadata(jpegKey, MediaType.IMAGE_JPEG, 123, null));
        });
        when(backup.createAccessUrl(jpegKey)).thenReturn(R2_URL);

        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(R2_URL);

        ArgumentCaptor<ImageLookupBudget> budgets = ArgumentCaptor.forClass(ImageLookupBudget.class);
        verify(storage).exists(eq(DETAIL), budgets.capture());
        verify(backup).findMetadata(eq(ORIGINAL), budgets.capture());
        verify(backup).findMetadata(eq(jpegKey), budgets.capture());
        verify(backup).createAccessUrl(jpegKey);
        assertThat(budgets.getAllValues()).hasSize(3)
                .allMatch(budget -> budget == budgets.getAllValues().getFirst());
        verifyNoMoreInteractions(storage, backup);
        verifyNoInteractions(primary);
    }

    @Test
    void singleLookupStopsOtherOriginalFormatsWhenFirstR2CandidateExhaustsBudget() {
        AtomicLong time = useFakeTime();
        when(backup.findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            return Optional.empty();
        });
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);

        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(S3_URL);

        verify(storage).exists(eq(DETAIL), any(ImageLookupBudget.class));
        verify(backup).findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(storage, backup);
        verify(primary).createAccessUrl(DETAIL);
    }

    @Test
    void eachSingleLookupReceivesAnIndependentBudget() {
        AtomicLong time = useFakeTime();
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            ImageLookupBudget budget = invocation.getArgument(1);
            assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(2));
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            throw new ImageLookupBudgetExceededException();
        });
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);

        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(S3_URL);
        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(S3_URL);

        ArgumentCaptor<ImageLookupBudget> budgets = ArgumentCaptor.forClass(ImageLookupBudget.class);
        verify(storage, times(2)).exists(eq(DETAIL), budgets.capture());
        assertThat(budgets.getAllValues().get(0)).isNotSameAs(budgets.getAllValues().get(1));
        verifyNoInteractions(backup);
    }

    @Test
    void singleAndListLookupsUseTheirOwnConfiguredBudgets() {
        provider = new R2FallbackImageUrlProvider(primary, storage, backup, s3("dev"),
                r2("dev", Duration.ofSeconds(2), Duration.ofMillis(500)), () -> 0);
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenReturn(true);
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);

        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(S3_URL);
        assertThat(provider.forResponse().createAccessUrl(DETAIL)).isSameAs(S3_URL);

        ArgumentCaptor<ImageLookupBudget> budgets = ArgumentCaptor.forClass(ImageLookupBudget.class);
        verify(storage, times(2)).exists(eq(DETAIL), budgets.capture());
        assertThat(budgets.getAllValues().get(0).requestTimeout(Duration.ofSeconds(10)))
                .isEqualTo(Duration.ofMillis(500));
        assertThat(budgets.getAllValues().get(1).requestTimeout(Duration.ofSeconds(10)))
                .isEqualTo(Duration.ofSeconds(2));
        verifyNoInteractions(backup);
    }

    @Test
    void exhaustedSingleBudgetDoesNotRepeatFailedS3Signing() {
        AtomicLong time = useFakeTime();
        ImageStorageException signingFailure = new ImageStorageException("signing failure", null,
                DiagnosticType.AUTHENTICATION_ERROR);
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenReturn(true);
        when(primary.createAccessUrl(DETAIL)).thenAnswer(invocation -> {
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            throw signingFailure;
        });

        assertThatThrownBy(() -> provider.createAccessUrl(DETAIL)).isSameAs(signingFailure);

        verify(primary).createAccessUrl(DETAIL);
        verifyNoMoreInteractions(primary);
        verifyNoInteractions(backup);
    }

    @Test
    void oneSlowS3HeadStopsAllFurtherHeadsForThirtyImages(CapturedOutput output) {
        AtomicLong time = useFakeTime();
        when(storage.exists(eq(THUMBNAIL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            ImageLookupBudget budget = invocation.getArgument(1);
            assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(2));
            time.addAndGet(Duration.ofSeconds(20).toNanos()); // SDK가 예산을 초과해 종료한 경우도 허용한다.
            throw new ImageStorageException("timeout", null, DiagnosticType.CLIENT_ERROR);
        });
        when(primary.createAccessUrl(THUMBNAIL)).thenReturn(S3_URL);
        ImageUrlProvider response = provider.forResponse();

        assertThat(IntStream.range(0, 30).mapToObj(i -> response.createAccessUrl(THUMBNAIL)).toList())
                .hasSize(30).allMatch(url -> url == S3_URL);

        verify(storage).exists(eq(THUMBNAIL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(storage);
        verifyNoInteractions(backup);
        verify(primary, times(30)).createAccessUrl(THUMBNAIL);
        assertThat(output.getOut()).contains("r2Result=BUDGET_EXHAUSTED", "result=S3_FALLBACK");
    }

    @Test
    void exhaustedR2CandidateStopsOtherFormatsAndNextImageHeads() {
        AtomicLong time = useFakeTime();
        when(backup.findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            return Optional.empty();
        });
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);
        ImageUrlProvider response = provider.forResponse();
        assertThat(response.createAccessUrl(DETAIL)).isSameAs(S3_URL);
        assertThat(response.createAccessUrl(DETAIL)).isSameAs(S3_URL);
        verify(storage).exists(eq(DETAIL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(storage);
        verify(backup).findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(backup);
    }

    @Test
    void remainingBudgetStillAllowsR2AndIsSharedWithNextImage() {
        AtomicLong time = useFakeTime();
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            time.addAndGet(Duration.ofMillis(500).toNanos());
            return false;
        });
        when(backup.findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            ImageLookupBudget budget = invocation.getArgument(1);
            assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofMillis(1500));
            time.addAndGet(Duration.ofMillis(1500).toNanos());
            return Optional.of(new BackupObjectMetadata(ORIGINAL, MediaType.IMAGE_PNG, 123, null));
        });
        when(backup.createAccessUrl(ORIGINAL)).thenReturn(R2_URL);
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);
        ImageUrlProvider response = provider.forResponse();
        assertThat(response.createAccessUrl(DETAIL)).isSameAs(R2_URL);
        assertThat(response.createAccessUrl(DETAIL)).isSameAs(S3_URL);
        verify(storage).exists(eq(DETAIL), any(ImageLookupBudget.class));
        verify(backup).findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class));
        verify(backup).createAccessUrl(ORIGINAL);
        verifyNoMoreInteractions(storage, backup);
    }

    @Test
    void newResponsesReceiveIndependentBudgets() {
        AtomicLong time = useFakeTime();
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenAnswer(invocation -> {
            ImageLookupBudget budget = invocation.getArgument(1);
            assertThat(budget.requestTimeout(Duration.ofSeconds(10))).isEqualTo(Duration.ofSeconds(2));
            time.addAndGet(Duration.ofSeconds(2).toNanos());
            throw new ImageLookupBudgetExceededException();
        });
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);
        assertThat(provider.forResponse().createAccessUrl(DETAIL)).isSameAs(S3_URL);
        assertThat(provider.forResponse().createAccessUrl(DETAIL)).isSameAs(S3_URL);
        verify(storage, times(2)).exists(eq(DETAIL), any(ImageLookupBudget.class));
        verifyNoInteractions(backup);
    }

    @Test
    void exhaustedBudgetStillRejectsOtherEnvironmentBeforeSigning() {
        AtomicLong time = useFakeTime();
        ImageUrlProvider response = provider.forResponse();
        time.addAndGet(Duration.ofSeconds(2).toNanos());
        assertThatThrownBy(() -> response.createAccessUrl(DETAIL.replace("/dev/", "/prod/")))
                .isInstanceOf(ImageStorageException.class);
        verifyNoInteractions(storage, backup, primary);
    }

    private AtomicLong useFakeTime() {
        AtomicLong time = new AtomicLong();
        provider = new R2FallbackImageUrlProvider(primary, storage, backup, s3("dev"), r2("dev"), time::get);
        return time;
    }

    @Test
    void normalS3OnlyChecksAndSignsS3() {
        when(storage.exists(eq(THUMBNAIL), any(ImageLookupBudget.class))).thenReturn(true);
        when(primary.createAccessUrl(THUMBNAIL)).thenReturn(S3_URL);
        assertThat(provider.createAccessUrl(THUMBNAIL)).isSameAs(S3_URL);
        verify(storage).exists(eq(THUMBNAIL), any(ImageLookupBudget.class));
        verify(primary).createAccessUrl(THUMBNAIL);
        verifyNoInteractions(backup);
    }

    @ParameterizedTest
    @CsvSource({"image-960.webp,image.png,image/png", "image-240.webp,image.png,image/png",
            "image-960.webp,image.jpg,image/jpeg", "image-240.webp,image.jpg,image/jpeg",
            "image-960.webp,image.webp,image/webp", "image-240.webp,image.webp,image/webp",
            "image.png,image.png,image/png", "image.jpg,image.jpg,image/jpeg", "image.webp,image.webp,image/webp"})
    void missingS3UsesOriginalKeyAndMime(String displayFilename, String originalFilename, String contentType) {
        String key = ROOT + originalFilename;
        found(key, MediaType.parseMediaType(contentType));
        assertThat(provider.createAccessUrl(ROOT + displayFilename)).isSameAs(R2_URL);
        verify(backup).findMetadata(eq(key), any(ImageLookupBudget.class));
        verify(backup).createAccessUrl(key);
        verify(backup, never()).download(anyString());
        verify(backup, never()).uploadIfAbsent(anyString(), any());
        verifyNoInteractions(primary);
        verify(storage, never()).restoreIfMissing(anyString(), any());
        verify(storage, never()).delete(anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    void preservesBothUuidFoldersAndCurrentEnvironment(String environment) {
        String key = "harudle/generated/diary-images/" + environment
                + "/550e8400-e29b-41d4-a716-446655440000/123e4567-e89b-12d3-a456-426614174000/image.jpg";
        provider = new R2FallbackImageUrlProvider(primary, storage, backup, s3(environment), r2(environment), () -> 0);
        found(key, MediaType.IMAGE_JPEG);
        assertThat(provider.createAccessUrl(key)).isSameAs(R2_URL);
        verify(backup).createAccessUrl(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLIENT_ERROR", "AUTHENTICATION_ERROR", "AUTHORIZATION_ERROR", "PROVIDER_ERROR"})
    void sourceFailureFallsBackOnce(String type) {
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenThrow(new ImageStorageException("S3 조회 실패", null, DiagnosticType.valueOf(type)));
        found(ORIGINAL, MediaType.IMAGE_PNG);
        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(R2_URL);
        verify(storage).exists(eq(DETAIL), any(ImageLookupBudget.class));
        verify(backup).findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class));
        verify(backup).createAccessUrl(ORIGINAL);
        verifyNoInteractions(primary);
    }

    @Test
    void signingFailureAlsoFallsBack() {
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenReturn(true);
        when(primary.createAccessUrl(DETAIL)).thenThrow(new ImageStorageException("서명 실패", null,
                DiagnosticType.AUTHENTICATION_ERROR));
        found(ORIGINAL, MediaType.IMAGE_PNG);
        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(R2_URL);
        verify(primary).createAccessUrl(DETAIL);
        verify(backup).createAccessUrl(ORIGINAL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "ERROR"})
    void noBackupReturnsS3UrlWithoutRepeatingHead(String sourceResult) {
        if ("ERROR".equals(sourceResult)) {
            when(storage.exists(eq(THUMBNAIL), any(ImageLookupBudget.class))).thenThrow(new ImageStorageException("S3 HEAD timeout", null,
                    DiagnosticType.CLIENT_ERROR));
        }
        when(primary.createAccessUrl(THUMBNAIL)).thenReturn(S3_URL);

        assertThat(provider.createAccessUrl(THUMBNAIL)).isSameAs(S3_URL);

        verify(storage).exists(eq(THUMBNAIL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(storage);
        verify(backup).findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class));
        verify(backup).findMetadata(eq(ROOT + "image.jpg"), any(ImageLookupBudget.class));
        verify(backup).findMetadata(eq(ROOT + "image.webp"), any(ImageLookupBudget.class));
        verify(backup, never()).createAccessUrl(anyString());
        verify(primary).createAccessUrl(THUMBNAIL);
        verifyNoMoreInteractions(primary);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTHENTICATION_ERROR", "AUTHORIZATION_ERROR", "CLIENT_ERROR", "CONFIGURATION_ERROR",
            "PROVIDER_ERROR", "REQUEST_PREPARATION_ERROR", "RESPONSE_PROCESSING_ERROR"})
    void backupErrorReturnsS3UrlWithoutTryingNextCandidate(String type) {
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);
        when(backup.findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class))).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.valueOf(type), new RuntimeException("provider error")));

        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(S3_URL);

        verify(backup).findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(backup);
        verify(primary).createAccessUrl(DETAIL);
        verify(storage).exists(eq(DETAIL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(storage);
    }

    @Test
    void backupSigningFailureReturnsS3UrlWithoutRetryingR2() {
        found(ORIGINAL, MediaType.IMAGE_PNG);
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);
        when(backup.createAccessUrl(ORIGINAL)).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.CLIENT_ERROR, new RuntimeException("timeout")));

        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(S3_URL);

        verify(backup).createAccessUrl(ORIGINAL);
        verify(backup, never()).findMetadata(eq(ROOT + "image.jpg"), any(ImageLookupBudget.class));
        verify(primary).createAccessUrl(DETAIL);
    }

    @Test
    void mismatchedBackupMetadataCannotSignAnotherObject() {
        when(primary.createAccessUrl(DETAIL)).thenReturn(S3_URL);
        when(backup.findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class))).thenReturn(Optional.of(new BackupObjectMetadata(
                ROOT + "image.jpg", MediaType.IMAGE_JPEG, 123, null)));

        assertThat(provider.createAccessUrl(DETAIL)).isSameAs(S3_URL);

        verify(backup).findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(backup);
    }

    @Test
    void failedS3SigningIsNotRepeatedWhenBackupIsMissing() {
        ImageStorageException signingFailure = new ImageStorageException("S3 signing failure", null,
                DiagnosticType.AUTHENTICATION_ERROR);
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenReturn(true);
        when(primary.createAccessUrl(DETAIL)).thenThrow(signingFailure);

        assertThatThrownBy(() -> provider.createAccessUrl(DETAIL)).isSameAs(signingFailure);

        verify(primary).createAccessUrl(DETAIL);
        verifyNoMoreInteractions(primary);
        verify(storage).exists(eq(DETAIL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(storage);
    }

    @Test
    void finalS3SigningFailureStillFailsAsStorageError() {
        ImageStorageException signingFailure = new ImageStorageException("S3 signing failure", null,
                DiagnosticType.CLIENT_ERROR);
        when(primary.createAccessUrl(DETAIL)).thenThrow(signingFailure);

        assertThatThrownBy(() -> provider.createAccessUrl(DETAIL)).isSameAs(signingFailure);

        verify(primary).createAccessUrl(DETAIL);
    }

    @Test
    void backupKeyValidationCannotBeBypassedByReturningS3Url() {
        when(backup.findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class))).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.REQUEST_VALIDATION_ERROR, new IllegalArgumentException("invalid")));

        assertThatThrownBy(() -> provider.createAccessUrl(DETAIL)).isInstanceOf(ImageStorageException.class)
                .extracting(exception -> ((ImageStorageException) exception).diagnosticType())
                .isEqualTo(DiagnosticType.REQUEST_VALIDATION_ERROR);

        verifyNoInteractions(primary);
        verify(backup).findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class));
        verifyNoMoreInteractions(backup);
    }

    @Test
    void requestValidationFailureCannotEscapeToR2() {
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenThrow(new ImageStorageException("invalid key", null,
                DiagnosticType.REQUEST_VALIDATION_ERROR));
        assertThatThrownBy(() -> provider.createAccessUrl(DETAIL)).isInstanceOf(ImageStorageException.class);
        verifyNoInteractions(backup, primary);
    }

    @ParameterizedTest
    @ValueSource(strings = {"harudle/generated/diary-images/prod/id/image.png",
            "harudle/references/generation/dev/image.png", "harudle/generated/diary-images/dev/../image.png",
            "harudle/generated/diary-images/dev/%2e%2e/image.png", "https://example/image.png"})
    void rejectsInvalidScopeBeforeAnyStorageCall(String key) {
        assertThatThrownBy(() -> provider.createAccessUrl(key)).isInstanceOf(ImageStorageException.class)
                .extracting(exception -> ((ImageStorageException) exception).diagnosticType())
                .isEqualTo(DiagnosticType.REQUEST_VALIDATION_ERROR);
        verifyNoInteractions(storage, primary, backup);
    }

    @Test
    void failsAtStartupForMixedEnvironments() {
        assertThatThrownBy(() -> new R2FallbackImageUrlProvider(primary, storage, backup, s3("dev"), r2("prod")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(storage, primary, backup);
    }

    @Test
    void neverLogsSignedUrlsOrProviderSecrets(CapturedOutput output) {
        String sensitive = "fake-secret https://example/image?X-Amz-Signature=private-signature";
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenThrow(new ImageStorageException(sensitive));
        found(ORIGINAL, MediaType.IMAGE_PNG);
        when(backup.createAccessUrl(ORIGINAL)).thenReturn(new ImageAccessUrl(URI.create(
                "https://example.r2.cloudflarestorage.com/image?X-Amz-Signature=private-signature"), Instant.MAX));
        provider.createAccessUrl(DETAIL);
        assertThat(output.getOut()).contains("image_url_selected", "s3Result=ERROR", "result=R2")
                .doesNotContain("fake-secret", "X-Amz-Signature", "private-signature");
    }

    @ParameterizedTest
    @ValueSource(strings = {"MISSING", "ERROR"})
    void logsBackupAbsenceSeparatelyFromErrorsWhenReturningS3Url(String backupResult, CapturedOutput output) {
        String sensitive = "fake-secret https://example/image?X-Amz-Signature=private-signature";
        when(storage.exists(eq(DETAIL), any(ImageLookupBudget.class))).thenThrow(new ImageStorageException(sensitive, null, DiagnosticType.CLIENT_ERROR));
        when(primary.createAccessUrl(DETAIL)).thenReturn(new ImageAccessUrl(URI.create(
                "https://example.s3.amazonaws.com/image?X-Amz-Signature=private-signature"), Instant.MAX));
        if ("ERROR".equals(backupResult)) {
            when(backup.findMetadata(eq(ORIGINAL), any(ImageLookupBudget.class))).thenThrow(new BackupStorageException(
                    BackupStorageException.FailureType.AUTHORIZATION_ERROR, new RuntimeException(sensitive)));
        }

        provider.createAccessUrl(DETAIL);

        assertThat(output.getOut()).contains("image_url_selected", "s3Result=ERROR", "result=S3_FALLBACK",
                "r2Result=" + backupResult,
                "r2FailureType=" + ("ERROR".equals(backupResult) ? "AUTHORIZATION_ERROR" : "none"))
                .doesNotContain("fake-secret", "X-Amz-Signature", "private-signature");
    }

    private void found(String key, MediaType mime) {
        when(backup.findMetadata(eq(key), any(ImageLookupBudget.class))).thenReturn(Optional.of(new BackupObjectMetadata(key, mime, 123, null)));
        when(backup.createAccessUrl(key)).thenReturn(R2_URL);
    }

    private S3StorageProperties s3(String environment) {
        return new S3StorageProperties("test-source", "ap-northeast-2", environment,
                "harudle/generated/diary-images/" + environment, "harudle/references/generation/" + environment,
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
    }

    private R2StorageProperties r2(String environment) {
        return r2(environment, Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    private R2StorageProperties r2(String environment, Duration listBudget, Duration singleBudget) {
        return new R2StorageProperties(true, environment, URI.create("https://example.r2.cloudflarestorage.com"),
                "test-backup", "fake-key", "fake-secret", Duration.ofMinutes(15), DataSize.ofMegabytes(20),
                listBudget, singleBudget);
    }
}
