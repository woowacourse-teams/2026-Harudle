package com.harudle.feed.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.feed.service.port.FeedPreviewImageRenderer;
import com.harudle.generation.diary.service.port.BackupObjectStorage;
import com.harudle.generation.diary.service.port.BackupStorageException;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class StoredFeedPreviewImageProviderTest {

    private static final String ROOT = "harudle/generated/diary-images/prod/private-key/";
    private static final String DETAIL = ROOT + "image-960.webp";
    private static final List<String> ORIGINALS = List.of(ROOT + "image.png", ROOT + "image.jpg", ROOT + "image.webp");
    private static final ReferenceImage JPEG = image(MediaType.IMAGE_JPEG);

    @Mock private ObjectProvider<ImageStorage> primaryStorages;
    @Mock private ObjectProvider<BackupObjectStorage> backups;
    @Mock private ImageStorage primary;
    @Mock private BackupObjectStorage backup;
    @Mock private FeedPreviewImageRenderer renderer;
    private StoredFeedPreviewImageProvider provider;

    @BeforeEach
    void setUp() {
        provider = new StoredFeedPreviewImageProvider(primaryStorages, backups, renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image.png", "image.jpg", "image.webp"})
    void selectsAnOriginalOnlyAfterSuccessfulJpegRendering(String filename) {
        String key = ROOT + filename;
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(anyString())).thenAnswer(call -> key.equals(call.getArgument(0)));
        when(primary.load(key)).thenReturn(original);
        when(renderer.render(original)).thenReturn(JPEG);

        assertThat(provider.provide(DETAIL)).isSameAs(JPEG);

        var order = inOrder(primary, renderer);
        for (String candidate : ORIGINALS) {
            order.verify(primary).exists(candidate);
            if (candidate.equals(key)) {
                order.verify(primary).load(key);
                order.verify(renderer).render(original);
                break;
            }
        }
        verifyNoInteractions(backups, backup);
    }

    @Test
    void corruptOriginalCanFallBackToHealthyPrimaryDetail(CapturedOutput output) {
        ReferenceImage corrupt = image(MediaType.IMAGE_PNG);
        ReferenceImage detail = image(MediaType.parseMediaType("image/webp"));
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(anyString())).thenAnswer(call ->
                ORIGINALS.getFirst().equals(call.getArgument(0)) || DETAIL.equals(call.getArgument(0)));
        when(primary.load(ORIGINALS.getFirst())).thenReturn(corrupt);
        when(primary.load(DETAIL)).thenReturn(detail);
        when(renderer.render(corrupt)).thenThrow(new ImageStorageException("secret source URL " + DETAIL));
        when(renderer.render(detail)).thenReturn(JPEG);

        assertThat(provider.provide(DETAIL)).isSameAs(JPEG);

        var order = inOrder(primary, renderer);
        order.verify(primary).exists(ORIGINALS.getFirst());
        order.verify(primary).load(ORIGINALS.getFirst());
        order.verify(renderer).render(corrupt);
        order.verify(primary).exists(ORIGINALS.get(1));
        order.verify(primary).exists(ORIGINALS.get(2));
        order.verify(primary).exists(DETAIL);
        order.verify(primary).load(DETAIL);
        order.verify(renderer).render(detail);
        verifyNoInteractions(backups, backup);
        assertThat(output.getAll()).contains("source=S3", "stage=RENDER", "reason=render_failed")
                .doesNotContain("secret source URL", ROOT, DETAIL);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualCorruptOrOversizedPngCanFallBackToHealthyBackup(boolean oversized) throws IOException {
        byte[] invalid = {1, 2, 3};
        if (oversized) {
            invalid = validPng();
            ByteBuffer.wrap(invalid, 16, 8).putInt(6000).putInt(6000);
            CRC32 crc = new CRC32();
            crc.update(invalid, 12, 17);
            ByteBuffer.wrap(invalid, 29, 4).putInt((int) crc.getValue());
        }
        ReferenceImage unusable = new ReferenceImage(new ByteArrayResource(invalid), MediaType.IMAGE_PNG);
        ReferenceImage healthy = new ReferenceImage(new ByteArrayResource(validPng()), MediaType.IMAGE_PNG);
        provider = new StoredFeedPreviewImageProvider(primaryStorages, backups, new JpegFeedPreviewImageRenderer());
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenReturn(true);
        when(primary.load(ORIGINALS.getFirst())).thenReturn(unusable);
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenReturn(Optional.of(healthy));

        ReferenceImage result = provider.provide(DETAIL);

        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE_JPEG);
        assertThat(result.resource().contentLength()).isPositive();
        verify(backup).download(ORIGINALS.getFirst());
        verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void candidatePresenceOrLoadFailureDoesNotSkipLaterPrimaryCandidate(boolean presenceFailure) {
        ReferenceImage healthy = image(MediaType.IMAGE_JPEG);
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        if (presenceFailure) {
            when(primary.exists(ORIGINALS.getFirst())).thenThrow(new ImageStorageException("private presence"));
        } else {
            when(primary.exists(ORIGINALS.getFirst())).thenReturn(true);
            when(primary.load(ORIGINALS.getFirst())).thenThrow(new ImageStorageException("private load"));
        }
        when(primary.exists(ORIGINALS.get(1))).thenReturn(true);
        when(primary.load(ORIGINALS.get(1))).thenReturn(healthy);
        when(renderer.render(healthy)).thenReturn(JPEG);

        assertThat(provider.provide(DETAIL)).isSameAs(JPEG);

        verifyNoInteractions(backups, backup);
    }

    @Test
    void candidateBackupFailureDoesNotSkipLaterBackupCandidate() {
        ReferenceImage healthy = image(MediaType.IMAGE_JPEG);
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.PROVIDER_ERROR, new IOException("private backup endpoint")));
        when(backup.download(ORIGINALS.get(1))).thenReturn(Optional.of(healthy));
        when(renderer.render(healthy)).thenReturn(JPEG);

        assertThat(provider.provide(DETAIL)).isSameAs(JPEG);

        verify(backup).download(ORIGINALS.get(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "image/gif", "image/avif"})
    void unsupportedPrimaryTypeDoesNotPreventRenderingNextCandidate(String mediaType) {
        ReferenceImage unsupported = image(MediaType.parseMediaType(mediaType));
        ReferenceImage healthy = image(MediaType.IMAGE_JPEG);
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenReturn(true);
        when(primary.load(ORIGINALS.getFirst())).thenReturn(unsupported);
        when(primary.exists(ORIGINALS.get(1))).thenReturn(true);
        when(primary.load(ORIGINALS.get(1))).thenReturn(healthy);
        when(renderer.render(healthy)).thenReturn(JPEG);

        assertThat(provider.provide(DETAIL)).isSameAs(JPEG);

        verify(renderer, never()).render(unsupported);
    }

    @Test
    void unsupportedBackupTypeDoesNotPreventRenderingNextCandidate() {
        ReferenceImage unsupported = image(MediaType.parseMediaType("image/svg+xml"));
        ReferenceImage healthy = image(MediaType.IMAGE_JPEG);
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenReturn(Optional.of(unsupported));
        when(backup.download(ORIGINALS.get(1))).thenReturn(Optional.of(healthy));
        when(renderer.render(healthy)).thenReturn(JPEG);

        assertThat(provider.provide(DETAIL)).isSameAs(JPEG);

        verify(renderer, never()).render(unsupported);
    }

    @Test
    void legacyKeyIsReadAndRenderedWithoutInventingAnotherDirectory() {
        String key = ROOT + "generation-123.webp";
        ReferenceImage original = image(MediaType.parseMediaType("image/webp"));
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(key)).thenReturn(true);
        when(primary.load(key)).thenReturn(original);
        when(renderer.render(original)).thenReturn(JPEG);

        assertThat(provider.provide(key)).isSameAs(JPEG);

        verify(primary).exists(key);
        verifyNoInteractions(backups, backup);
    }

    @Test
    void allUnavailableCandidatesReturnFixedErrorAndSafeDiagnostic(CapturedOutput output) {
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenThrow(new ImageStorageException("secret-primary " + ROOT));
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenThrow(new BackupStorageException(
                BackupStorageException.FailureType.PROVIDER_ERROR,
                new IOException("https://secret-backup.example/?token=must-not-leak")));

        assertThatThrownBy(() -> provider.provide(DETAIL))
                .isInstanceOf(ImageStorageException.class)
                .hasMessage("공유 미리보기 이미지를 읽을 수 없습니다.").hasNoCause();

        assertThat(output.getAll()).contains("source=S3", "source=R2", "reason=storage_failed",
                        "reason=no_usable_candidate")
                .doesNotContain(ROOT, "secret-primary", "secret-backup", "must-not-leak");
    }

    @Test
    void missingAdaptersFailOnlyOnUse() {
        assertThatThrownBy(() -> provider.provide(DETAIL)).isInstanceOf(ImageStorageException.class).hasNoCause();
        verifyNoInteractions(renderer);
    }

    @Test
    void unexpectedPrimaryProgrammingErrorIsNotTurnedIntoFallback() {
        IllegalStateException bug = new IllegalStateException("unexpected primary invariant");
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenThrow(bug);

        assertThatThrownBy(() -> provider.provide(DETAIL)).isSameAs(bug);

        verifyNoInteractions(backups, backup, renderer);
    }

    @Test
    void unexpectedRendererProgrammingErrorIsNotTurnedIntoFallback() {
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        NullPointerException bug = new NullPointerException("unexpected renderer invariant");
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenReturn(true);
        when(primary.load(ORIGINALS.getFirst())).thenReturn(original);
        when(renderer.render(original)).thenThrow(bug);

        assertThatThrownBy(() -> provider.provide(DETAIL)).isSameAs(bug);

        verifyNoInteractions(backups, backup);
    }

    @Test
    void unexpectedBackupProgrammingErrorIsNotTurnedIntoUnavailable() {
        IllegalStateException bug = new IllegalStateException("unexpected backup invariant");
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenThrow(bug);

        assertThatThrownBy(() -> provider.provide(DETAIL)).isSameAs(bug);
    }

    @Test
    void interruptedStorageFailureRestoresFlagAndStopsAllFallback() {
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenThrow(new ImageStorageException(
                "private interruption", new InterruptedException("private cause")));
        try {
            assertThatThrownBy(() -> provider.provide(DETAIL)).isInstanceOf(ImageStorageException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(primary, never()).exists(ORIGINALS.get(1));
            verifyNoInteractions(backups, backup, renderer);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void socketTimeoutIsRecoverableAndDoesNotMarkThreadInterrupted() {
        ReferenceImage healthy = image(MediaType.IMAGE_JPEG);
        when(primaryStorages.getIfAvailable()).thenReturn(primary);
        when(primary.exists(ORIGINALS.getFirst())).thenThrow(new ImageStorageException(
                "private timeout", new SocketTimeoutException("private cause")));
        when(backups.getIfAvailable()).thenReturn(backup);
        when(backup.download(ORIGINALS.getFirst())).thenReturn(Optional.of(healthy));
        when(renderer.render(healthy)).thenReturn(JPEG);

        assertThat(provider.provide(DETAIL)).isSameAs(JPEG);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    private static byte[] validPng() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB), "png", output)).isTrue();
        return output.toByteArray();
    }

    private static ReferenceImage image(MediaType type) {
        return new ReferenceImage(new ByteArrayResource(new byte[]{1, 2, 3}), type);
    }
}
