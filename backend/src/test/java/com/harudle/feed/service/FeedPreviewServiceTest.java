package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.category.service.port.CategoryReader.Category;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedPreviewImageReader;
import com.harudle.feed.service.port.FeedPreviewImageRenderer;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

@ExtendWith(MockitoExtension.class)
class FeedPreviewServiceTest {

    private static final UUID FEED_ID = UUID.randomUUID();
    private static final String IMAGE_KEY = "harudle/generated/diary-images/prod/test/image-960.webp";
    private static final FeedResult FEED = new FeedResult(FEED_ID,
            new Profile(UUID.randomUUID(), "네오", URI.create("https://example.com/profile.png"), null),
            new Category(1, "일상", 1, true), IMAGE_KEY, Instant.parse("2026-11-17T00:00:00Z"), 0, 0, false, false);

    @Mock private FeedQueryService feeds;
    @Mock private FeedPreviewImageReader images;
    @Mock private FeedPreviewImageRenderer renderer;
    private FeedPreviewService service;

    @BeforeEach
    void setUp() {
        service = new FeedPreviewService(feeds, images, renderer);
    }

    @Test
    void buildsMetadataFromAnonymousPublicFeedWithoutReadingImageOrDiaryText() {
        when(feeds.getDetail(null, FEED_ID)).thenReturn(FEED);

        var metadata = service.metadata(FEED_ID);

        assertThat(metadata.title()).isEqualTo("네오님의 네컷만화 | 하루들");
        assertThat(metadata.description()).isEqualTo("일상에서 공유한 네컷만화를 확인해 보세요.");
        verifyNoInteractions(images, renderer);
    }

    @Test
    void checksPublicVisibilityBeforeReadingStoredKeyAndRenderingJpeg() {
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        ReferenceImage jpeg = image(MediaType.IMAGE_JPEG);
        when(feeds.getDetail(null, FEED_ID)).thenReturn(FEED);
        when(images.read(IMAGE_KEY)).thenReturn(original);
        when(renderer.render(original)).thenReturn(jpeg);

        assertThat(service.image(FEED_ID)).isSameAs(jpeg);

        var order = inOrder(feeds, images, renderer);
        order.verify(feeds).getDetail(null, FEED_ID);
        order.verify(images).read(IMAGE_KEY);
        order.verify(renderer).render(original);
    }

    @Test
    void hiddenOrDeletedFeedCannotReadImageOrProduceMetadata() {
        when(feeds.getDetail(null, FEED_ID)).thenThrow(new FeedNotFoundException());

        assertThatThrownBy(() -> service.metadata(FEED_ID)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> service.image(FEED_ID)).isInstanceOf(FeedNotFoundException.class);

        verifyNoInteractions(images, renderer);
    }

    @Test
    void imageReadFailureDoesNotRenderAnotherImage() {
        when(feeds.getDetail(null, FEED_ID)).thenReturn(FEED);
        when(images.read(IMAGE_KEY)).thenThrow(new ImageStorageException("공유 미리보기 이미지를 읽을 수 없습니다."));

        assertThatThrownBy(() -> service.image(FEED_ID)).isInstanceOf(ImageStorageException.class);

        verifyNoInteractions(renderer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read", "render"})
    void limitsWholeImageReadAndRenderToTwoRequestsWithoutQueueing(String blockedPhase) throws Exception {
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        ReferenceImage jpeg = image(MediaType.IMAGE_JPEG);
        CountDownLatch reading = new CountDownLatch(2);
        CountDownLatch finish = new CountDownLatch(1);
        when(feeds.getDetail(null, FEED_ID)).thenReturn(FEED);
        when(images.read(IMAGE_KEY)).thenAnswer(invocation -> {
            if (blockedPhase.equals("read")) {
                holdImageRequest(reading, finish);
            }
            return original;
        });
        when(renderer.render(original)).thenAnswer(invocation -> {
            if (blockedPhase.equals("render")) {
                holdImageRequest(reading, finish);
            }
            return jpeg;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.image(FEED_ID));
            var second = executor.submit(() -> service.image(FEED_ID));
            try {
                assertThat(reading.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.image(FEED_ID)).isInstanceOf(ImageStorageException.class)
                        .hasMessage("공유 미리보기 이미지 요청이 많습니다. 잠시 후 다시 시도해 주세요.");
                verify(feeds, times(3)).getDetail(null, FEED_ID);
                verify(images, times(2)).read(IMAGE_KEY);
            } finally {
                finish.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(jpeg);
            assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(jpeg);
        }
        assertThat(service.image(FEED_ID)).isSameAs(jpeg);
    }

    @Test
    void failedReadsReleaseCapacityForFollowingRequests() {
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        ReferenceImage jpeg = image(MediaType.IMAGE_JPEG);
        when(feeds.getDetail(null, FEED_ID)).thenReturn(FEED);
        when(images.read(IMAGE_KEY)).thenThrow(new ImageStorageException("읽기 실패"))
                .thenThrow(new ImageStorageException("다시 읽기 실패")).thenReturn(original);
        when(renderer.render(original)).thenReturn(jpeg);

        assertThatThrownBy(() -> service.image(FEED_ID)).hasMessage("읽기 실패");
        assertThatThrownBy(() -> service.image(FEED_ID)).hasMessage("다시 읽기 실패");
        assertThat(service.image(FEED_ID)).isSameAs(jpeg);
    }

    @Test
    void failedRendersAlsoReleaseCapacityForFollowingRequests() {
        ReferenceImage original = image(MediaType.IMAGE_PNG);
        ReferenceImage jpeg = image(MediaType.IMAGE_JPEG);
        when(feeds.getDetail(null, FEED_ID)).thenReturn(FEED);
        when(images.read(IMAGE_KEY)).thenReturn(original);
        when(renderer.render(original)).thenThrow(new ImageStorageException("변환 실패"))
                .thenThrow(new ImageStorageException("다시 변환 실패")).thenReturn(jpeg);

        assertThatThrownBy(() -> service.image(FEED_ID)).hasMessage("변환 실패");
        assertThatThrownBy(() -> service.image(FEED_ID)).hasMessage("다시 변환 실패");
        assertThat(service.image(FEED_ID)).isSameAs(jpeg);
    }

    private static ReferenceImage image(MediaType mediaType) {
        return new ReferenceImage(new ByteArrayResource(new byte[] {1, 2, 3}), mediaType);
    }

    private static void holdImageRequest(CountDownLatch entered, CountDownLatch finish) throws InterruptedException {
        entered.countDown();
        if (!finish.await(5, TimeUnit.SECONDS)) {
            throw new AssertionError("미리보기 처리 종료 신호를 기다리지 못했습니다.");
        }
    }
}
