package com.harudle.generation.adapter.out.s3;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.common.logging.ExternalApiLogger;
import com.harudle.generation.config.S3StorageProperties;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

class S3ImageBoundaryTest {

    private final S3Client client = mock(S3Client.class);
    private final S3Presigner presigner = mock(S3Presigner.class);
    private final ImageUploadPreparer preparer = mock(ImageUploadPreparer.class);
    private final S3FailureReporter reporter = new S3FailureReporter(
            new S3ExceptionTranslator(), mock(ExternalApiLogger.class)
    );
    private final GeneratedImage image = new GeneratedImage(new ByteArrayResource(new byte[]{1}), MediaType.IMAGE_PNG);

    @ParameterizedTest
    @CsvSource({"dev, prod", "prod, dev"})
    @DisplayName("반대 환경은 조회·존재 확인·복구·삭제·서명 전에 차단한다")
    void rejectsOtherEnvironmentBeforeAnyRequest(String environment, String other) {
        rejectAllOperations(environment, "harudle/generated/diary-images/" + other + "/id/image-960.webp");
        rejectAllOperations(environment, "harudle/references/generation/" + other + "/reference.png");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ",
            "harudle/generated/diary-images/id/image-960.webp",
            "harudle/generated/diary-images/dev-other/id/image-960.webp",
            "harudle/generated/diary-images/dev/../prod/id/image-960.webp",
            "harudle/generated/diary-images/dev/./id/image-960.webp",
            "harudle/generated/diary-images/dev//id/image-960.webp",
            "harudle/generated/diary-images/dev/id\\image-960.webp",
            "harudle/generated/diary-images/dev/%2e%2e/prod/image-960.webp",
            "harudle/generated/diary-images/dev/id/\nimage-960.webp",
            "harudle/generated/diary-images/dev/",
            "other-project/image-960.webp"
    })
    @DisplayName("공용·다른 프로젝트·모호한 경로는 S3와 서명기에 전달하지 않는다")
    void rejectsInvalidKeysBeforeAnyRequest(String key) {
        rejectAllOperations("dev", key);
    }

    @Test
    @DisplayName("UTF-8 1024바이트를 넘는 경로는 모든 접근에서 차단한다")
    void rejectsOversizedKey() {
        rejectAllOperations("dev", "harudle/generated/diary-images/dev/" + "가".repeat(340) + "/image-960.webp");
    }

    @ParameterizedTest
    @CsvSource({"dev, prod", "prod, dev"})
    @DisplayName("업로드 묶음에 반대 환경이 하나라도 있으면 첫 파일도 저장하지 않는다")
    void validatesWholeUploadBeforeWriting(String environment, String other) {
        String ownKey = "harudle/generated/diary-images/" + environment + "/id/image.png";
        String otherKey = "harudle/generated/diary-images/" + other + "/id/image-960.webp";
        when(preparer.prepare(any(), any())).thenReturn(new ImageUploadPreparer.UploadPlan(otherKey, List.of(
                new ImageUploadPreparer.Upload(ownKey, image), new ImageUploadPreparer.Upload(otherKey, image)
        )));

        assertThatThrownBy(() -> storage(environment).store(UUID.randomUUID(), image))
                .isInstanceOf(ImageStorageException.class);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev", "prod"})
    @DisplayName("자기 환경의 생성 이미지와 기준 이미지는 존재 확인과 누락 복구를 허용한다")
    void acceptsOwnImages(String environment) {
        S3ImageStorage storage = storage(environment);
        String generated = "harudle/generated/diary-images/" + environment + "/id/image.png";
        String reference = "harudle/references/generation/" + environment + "/reference.png";

        assertThat(storage.exists(generated)).isTrue();
        assertThat(storage.exists(reference)).isTrue();
        assertThat(storage.restoreIfMissing(generated, image)).isTrue();
        assertThat(storage.restoreIfMissing(reference, image)).isTrue();
    }

    @Test
    @DisplayName("기준 이미지는 일반 저장·삭제·공개 URL 발급 대상이 아니다")
    void protectsReferenceFromGeneratedImageOperations() {
        String reference = "harudle/references/generation/dev/reference.png";
        when(preparer.prepare(any(), any())).thenReturn(new ImageUploadPreparer.UploadPlan(reference,
                List.of(new ImageUploadPreparer.Upload(reference, image))));

        assertThatThrownBy(() -> storage("dev").store(UUID.randomUUID(), image))
                .isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> storage("dev").delete(reference)).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> urls("dev").createAccessUrl(reference)).isInstanceOf(ImageStorageException.class);
        verifyNoInteractions(client, presigner);
    }

    private void rejectAllOperations(String environment, String key) {
        S3ImageStorage storage = storage(environment);
        assertThatThrownBy(() -> storage.load(key)).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> storage.exists(key)).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> storage.restoreIfMissing(key, image)).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> storage.restoreOptimizedIfMissing(key, image))
                .isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> storage.restoreMissingThumbnail(key)).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(ImageStorageException.class);
        assertThatThrownBy(() -> urls(environment).createAccessUrl(key)).isInstanceOf(ImageStorageException.class);
        verifyNoInteractions(client, presigner, preparer);
    }

    private S3ImageStorage storage(String environment) {
        return new S3ImageStorage(client, properties(environment), preparer, reporter);
    }

    private S3ImageUrlProvider urls(String environment) {
        return new S3ImageUrlProvider(presigner, properties(environment), reporter);
    }

    private S3StorageProperties properties(String environment) {
        return new S3StorageProperties("test-bucket", "ap-northeast-2", environment,
                "harudle/generated/diary-images/" + environment, "harudle/references/generation/" + environment,
                DataSize.ofMegabytes(20), Duration.ofMinutes(15));
    }
}
