package com.harudle.feed.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

@ExtendWith(MockitoExtension.class)
class StoredFeedPreviewImageReaderTest {

    private static final String ROOT = "harudle/generated/diary-images/prod/test/";
    private static final String DETAIL = ROOT + "image-960.webp";
    private static final List<String> ORIGINALS = List.of(ROOT + "image.png", ROOT + "image.jpg", ROOT + "image.webp");
    private static final String ERROR = "공유 미리보기 이미지를 읽을 수 없습니다.";

    @Mock private ObjectProvider<ImageStorage> primaryStorages;
    @Mock private ObjectProvider<BackupObjectStorage> backups;
    @Mock private ImageStorage primary;
    @Mock private BackupObjectStorage backup;
    private StoredFeedPreviewImageReader reader;

    @BeforeEach
    void setUp() {
        reader = new StoredFeedPreviewImageReader(primaryStorages, backups);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.png", "image.jpg", "image.webp"})
    void prefersAvailableOriginalOverDetailWithoutDownloadingFromBackup(String filename) {
        String key = ROOT + filename;
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(anyString())).thenAnswer(invocation -> key.equals(invocation.getArgument(0)));
        when(primary.load(key)).thenReturn(original);

        assertThat(reader.read(DETAIL)).isSameAs(original);

        var order = inOrder(primary);
        for (String candidate : ORIGINALS) {
            order.verify(primary).exists(candidate);
            if (candidate.equals(key)) {
                order.verify(primary).load(key);
                break;
            }
        }
        verifyNoMoreInteractions(primary);
        verifyNoInteractions(backups, backup);
    }

    @Test
    void usesCurrentDetailOnlyWhenOriginalCandidatesAreMissing() {
        ReferenceImage detail = image(MediaType.parseMediaType("image/webp"));
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(anyString())).thenAnswer(invocation -> DETAIL.equals(invocation.getArgument(0)));
        when(primary.load(DETAIL)).thenReturn(detail);

        assertThat(reader.read(DETAIL)).isSameAs(detail);

        var order = inOrder(primary);
        for (String candidate : ORIGINALS) {
            order.verify(primary).exists(candidate);
        }
        order.verify(primary).exists(DETAIL);
        order.verify(primary).load(DETAIL);
        verifyNoMoreInteractions(primary);
        verifyNoInteractions(backups, backup);
    }

    @Test
    void fallsBackToBackupWhenS3PresenceCheckFailsWithoutRestoringOrWriting() {
        ReferenceImage original = image(MediaType.IMAGE_JPEG);
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenThrow(new ImageStorageException("internal S3 key and URL"));
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenReturn(Optional.empty());
        when(backup.download(ORIGINALS.get(1))).thenReturn(Optional.of(original));

        assertThat(reader.read(DETAIL)).isSameAs(original);

        verify(primary).exists(ORIGINALS.getFirst());
        verify(backup).download(ORIGINALS.getFirst());
        verify(backup).download(ORIGINALS.get(1));
        verifyNoMoreInteractions(primary, backup);
    }

    @Test
    void fallsBackToBackupWhenLoadFailsAfterExistenceCheck() {
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenReturn(true);
        when(primary.load(ORIGINALS.getFirst())).thenThrow(new ImageStorageException("S3 request failed"));
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenReturn(Optional.of(original));

        assertThat(reader.read(DETAIL)).isSameAs(original);

        verify(primary, never()).exists(DETAIL);
        verifyNoMoreStorageWrites();
    }

    @Test
    void readsLegacyOriginalKeyOnceWithoutInventingAnotherFolder() {
        String key = ORIGINALS.getFirst();
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(key)).thenReturn(true);
        when(primary.load(key)).thenReturn(image(MediaType.IMAGE_PNG));

        assertThat(reader.read(key).mediaType()).isEqualTo(MediaType.IMAGE_PNG);

        verify(primary).exists(key);
        verify(primary).load(key);
        verifyNoMoreInteractions(primary);
    }

    @Test
    void readsImageKeyFromBeforeOriginalAndDetailNamingWasIntroduced() {
        String key = ROOT + "generation-123.webp";
        ReferenceImage image = image(MediaType.parseMediaType("image/webp"));
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(key)).thenReturn(true);
        when(primary.load(key)).thenReturn(image);

        assertThat(reader.read(key)).isSameAs(image);

        verify(primary).exists(key);
        verify(primary).load(key);
        verifyNoMoreInteractions(primary);
        verifyNoInteractions(backups, backup);
    }

    @Test
    void legacyObjectMissingFromS3UsesTheSameKeyInR2() {
        String key = ROOT + "generation-123.webp";
        ReferenceImage image = image(MediaType.parseMediaType("image/webp"));
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(key)).thenReturn(Optional.of(image));

        assertThat(reader.read(key)).isSameAs(image);

        verify(primary).exists(key);
        verify(backup).download(key);
        verifyNoMoreInteractions(primary, backup);
    }

    @Test
    void missingAdaptersDoNotPreventConstructionAndFailWithFixedStorageErrorOnUse() {
        assertThatThrownBy(() -> reader.read(DETAIL)).isInstanceOf(ImageStorageException.class)
                .hasMessage(ERROR).hasNoCause();
    }

    @Test
    void readsR2WhenS3AdapterIsNotConfigured() {
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenReturn(Optional.of(original));

        assertThat(reader.read(DETAIL)).isSameAs(original);

        verifyNoInteractions(primary);
    }

    @Test
    void missingObjectsAndBackupFailureUseFixedErrorWithoutSourceKeyOrUrl() {
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(anyString())).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.PROVIDER_ERROR,
                new IllegalStateException("https://secret.example/" + DETAIL)));

        assertThatThrownBy(() -> reader.read(DETAIL)).isInstanceOf(ImageStorageException.class)
                .hasMessage(ERROR).hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "image/gif", "image/avif"})
    void rejectsUnsupportedS3FormatsAndTriesBackup(String mediaType) {
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenReturn(true);
        when(primary.load(ORIGINALS.getFirst())).thenReturn(image(MediaType.parseMediaType(mediaType)));

        assertThatThrownBy(() -> reader.read(DETAIL)).isInstanceOf(ImageStorageException.class).hasMessage(ERROR);

        verify(backups).getIfAvailable();
    }

    @Test
    void rejectsSvgFromBackupAsWell() {
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenReturn(Optional.of(image(MediaType.parseMediaType("image/svg+xml"))));

        assertThatThrownBy(() -> reader.read(DETAIL)).isInstanceOf(ImageStorageException.class).hasMessage(ERROR);
    }

    private void verifyNoMoreStorageWrites() {
        verify(primary).exists(ORIGINALS.getFirst());
        verify(primary).load(ORIGINALS.getFirst());
        verify(backup).download(ORIGINALS.getFirst());
        verifyNoMoreInteractions(primary, backup);
    }

    private static ReferenceImage image(MediaType mediaType) {
        return new ReferenceImage(new ByteArrayResource(new byte[] {1, 2, 3}), mediaType);
    }
}
