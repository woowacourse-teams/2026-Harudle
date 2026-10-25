package com.harudle.push.infrastructure;

import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushFcmOptions;
import com.google.firebase.messaging.WebpushNotification;
import com.harudle.feed.configuration.FeedUrlProperties;
import java.net.URI;
import java.util.UUID;

class FcmFeedPushSender {

    private static final String TITLE = "새 만화가 올라왔어요";
    private static final String BODY = "새 피드를 확인해 보세요.";
    private static final String WEB_TTL_SECONDS = "604800";

    private final FirebaseMessaging messaging;
    private final FeedUrlProperties feedUrls;

    FcmFeedPushSender(FirebaseMessaging messaging, FeedUrlProperties feedUrls) {
        this.messaging = messaging;
        this.feedUrls = feedUrls;
    }

    String send(String fid, UUID eventId, UUID feedId) throws FirebaseMessagingException {
        return messaging.send(message(fid, eventId, feedId));
    }

    private Message message(String fid, UUID eventId, UUID feedId) {
        return Message.builder().setFid(fid)
                .setNotification(Notification.builder().setTitle(TITLE).setBody(BODY).build())
                .putData("eventId", eventId.toString()).putData("type", "FEED_PUBLISHED")
                .putData("feedId", feedId.toString())
                .setWebpushConfig(webpush(eventId, feedId)).build();
    }

    private WebpushConfig webpush(UUID eventId, UUID feedId) {
        WebpushConfig.Builder builder = WebpushConfig.builder().putHeader("TTL", WEB_TTL_SECONDS)
                .setNotification(WebpushNotification.builder().setTitle(TITLE).setBody(BODY)
                        .setTag(eventId.toString()).build());
        URI link = feedUrls.shareUrl(feedId);
        if ("https".equalsIgnoreCase(link.getScheme())) {
            builder.setFcmOptions(WebpushFcmOptions.builder().setLink(link.toString()).build());
        }
        return builder.build();
    }
}
