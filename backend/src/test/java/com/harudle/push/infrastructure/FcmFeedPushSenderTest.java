package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.Message;
import com.harudle.feed.configuration.FeedUrlProperties;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class FcmFeedPushSenderTest {

    @Test
    @DisplayName("FID로 보낸 웹 푸시에 이벤트 정보, 7일 TTL, 중복 교체 tag와 피드 링크를 담는다")
    void sendsFeedNotificationToFid() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        when(messaging.send(any(Message.class))).thenReturn("accepted-message-id");
        FcmFeedPushSender sender = new FcmFeedPushSender(messaging,
                new FeedUrlProperties(URI.create("https://www.harudle.com/feeds")));
        UUID eventId = UUID.randomUUID();
        UUID feedId = UUID.randomUUID();

        assertThat(sender.send("FID-1", eventId, feedId)).isEqualTo("accepted-message-id");

        ArgumentCaptor<Message> captured = ArgumentCaptor.forClass(Message.class);
        verify(messaging).send(captured.capture());
        Message message = captured.getValue();
        assertThat(ReflectionTestUtils.getField(message, "fid")).isEqualTo("FID-1");
        assertThat(ReflectionTestUtils.getField(message, "token")).isNull();
        assertThat(ReflectionTestUtils.getField(message, "data")).isEqualTo(Map.of(
                "eventId", eventId.toString(), "type", "FEED_PUBLISHED", "feedId", feedId.toString()));
        Object notification = ReflectionTestUtils.getField(message, "notification");
        assertThat(ReflectionTestUtils.getField(notification, "title")).isEqualTo("새 만화가 올라왔어요");
        assertThat(ReflectionTestUtils.getField(notification, "body")).isEqualTo("새 피드를 확인해 보세요.");

        Object webpush = ReflectionTestUtils.getField(message, "webpushConfig");
        assertThat(ReflectionTestUtils.getField(webpush, "headers"))
                .isEqualTo(Map.of("TTL", "604800"));
        Map<?, ?> webNotification = (Map<?, ?>) ReflectionTestUtils.getField(webpush, "notification");
        assertThat(webNotification.get("tag")).isEqualTo(eventId.toString());
        Object options = ReflectionTestUtils.getField(webpush, "fcmOptions");
        assertThat(ReflectionTestUtils.getField(options, "link"))
                .isEqualTo("https://www.harudle.com/feeds/" + feedId);
    }

    @Test
    @DisplayName("HTTP 로컬 주소에서는 FCM이 요구하는 HTTPS 클릭 링크를 보내지 않는다")
    void omitsInsecureLocalLink() throws Exception {
        FirebaseMessaging messaging = mock(FirebaseMessaging.class);
        FcmFeedPushSender sender = new FcmFeedPushSender(messaging,
                new FeedUrlProperties(URI.create("http://localhost:5173/feeds")));

        sender.send("FID-1", UUID.randomUUID(), UUID.randomUUID());

        ArgumentCaptor<Message> captured = ArgumentCaptor.forClass(Message.class);
        verify(messaging).send(captured.capture());
        Object webpush = ReflectionTestUtils.getField(captured.getValue(), "webpushConfig");
        assertThat(ReflectionTestUtils.getField(webpush, "fcmOptions")).isNull();
    }
}
