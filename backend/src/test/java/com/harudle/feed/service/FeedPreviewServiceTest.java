package com.harudle.feed.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.harudle.feed.service.dto.FeedPreviewDetails;
import com.harudle.feed.service.exception.FeedNotFoundException;
import com.harudle.feed.service.port.FeedPreviewImageProvider;
import com.harudle.feed.service.port.FeedPreviewReader;
import com.harudle.generation.diary.service.port.ImageStorageException;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;

@ExtendWith(MockitoExtension.class)
class FeedPreviewServiceTest {

    private static final UUID FEED_ID = UUID.randomUUID();
    private static final String IMAGE_KEY = "harudle/generated/diary-images/prod/test/image-960.webp";
    private static final FeedPreviewDetails FEED = new FeedPreviewDetails("네오", "일상", IMAGE_KEY);

    @Mock private FeedPreviewReader feeds;
    @Mock private FeedPreviewImageProvider images;
    private FeedPreviewService service;

    @BeforeEach
    void setUp() {
        service = new FeedPreviewService(feeds, images);
    }

    @Test
    @DisplayName("공개 피드의 닉네임과 카테고리만으로 메타를 만들고 이미지는 요청하지 않는다")
    void buildsMetadataWithoutRequestingImage() {
        when(feeds.read(FEED_ID)).thenReturn(FEED);

        var metadata = service.metadata(FEED_ID);

        assertThat(metadata.title()).isEqualTo("네오님의 네컷만화 | 하루들");
        assertThat(metadata.description()).isEqualTo("일상에서 공유한 네컷만화를 확인해 보세요.");
        verifyNoInteractions(images);
    }

    @Test
    @DisplayName("공개 여부 확인이 끝난 뒤 확인된 저장 키로 JPEG를 요청한다")
    void checksPublicVisibilityBeforeRequestingImage() {
        ReferenceImage jpeg = jpeg();
        when(feeds.read(FEED_ID)).thenReturn(FEED);
        when(images.provide(IMAGE_KEY)).thenReturn(jpeg);

        assertThat(service.image(FEED_ID)).isSameAs(jpeg);

        var order = inOrder(feeds, images);
        order.verify(feeds).read(FEED_ID);
        order.verify(images).provide(IMAGE_KEY);
    }

    @Test
    @DisplayName("숨김·삭제된 피드는 메타를 만들거나 이미지 제공기를 호출하지 않는다")
    void hiddenOrDeletedFeedCannotRequestImageOrProduceMetadata() {
        when(feeds.read(FEED_ID)).thenThrow(new FeedNotFoundException());

        assertThatThrownBy(() -> service.metadata(FEED_ID)).isInstanceOf(FeedNotFoundException.class);
        assertThatThrownBy(() -> service.image(FEED_ID)).isInstanceOf(FeedNotFoundException.class);

        verifyNoInteractions(images);
    }

    @Test
    @DisplayName("이미 제공한 이미지가 있어도 다음 요청에서 공개 여부를 다시 확인한다")
    void rechecksVisibilityBeforeReusingPreviouslyProvidedImage() {
        ReferenceImage jpeg = jpeg();
        when(feeds.read(FEED_ID)).thenReturn(FEED).thenThrow(new FeedNotFoundException());
        when(images.provide(IMAGE_KEY)).thenReturn(jpeg);
        assertThat(service.image(FEED_ID)).isSameAs(jpeg);
        clearInvocations(images);

        assertThatThrownBy(() -> service.image(FEED_ID)).isInstanceOf(FeedNotFoundException.class);

        verifyNoInteractions(images);
    }

    @Test
    @DisplayName("이미지 제공 실패를 성공 응답이나 다른 이미지로 대체하지 않는다")
    void propagatesImageFailureWithoutChangingMetadata() {
        when(feeds.read(FEED_ID)).thenReturn(FEED);
        when(images.provide(IMAGE_KEY)).thenThrow(new ImageStorageException("이미지 제공 실패"));

        assertThatThrownBy(() -> service.image(FEED_ID))
                .isInstanceOf(ImageStorageException.class).hasMessage("이미지 제공 실패");
        assertThat(service.metadata(FEED_ID).title()).isEqualTo("네오님의 네컷만화 | 하루들");
    }

    private static ReferenceImage jpeg() {
        return new ReferenceImage(new ByteArrayResource(new byte[] {1, 2, 3}), MediaType.IMAGE_JPEG);
    }
}
