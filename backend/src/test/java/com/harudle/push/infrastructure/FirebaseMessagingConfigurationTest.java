package com.harudle.push.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.harudle.feed.configuration.FeedUrlProperties;
import java.net.URI;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FirebaseMessagingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FirebaseMessagingConfiguration.class)
            .withBean(FeedUrlProperties.class,
                    () -> new FeedUrlProperties(URI.create("https://www.harudle.com/feeds")))
            .withBean("firebaseCredentials", GoogleCredentials.class,
                    () -> GoogleCredentials.create(new AccessToken("test", new Date(Long.MAX_VALUE))));

    @Test
    @DisplayName("FCM을 활성화하지 않으면 Firebase 인증 정보가 없어도 SDK를 초기화하지 않는다")
    void disabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(FirebaseApp.class);
            assertThat(context).doesNotHaveBean(FirebaseMessaging.class);
            assertThat(context).doesNotHaveBean(FcmFeedPushSender.class);
        });
    }

    @Test
    @DisplayName("FCM을 활성화하면 지정한 Firebase 프로젝트에 Admin SDK를 연결한다")
    void initializesWhenEnabled() {
        contextRunner.withPropertyValues(
                "harudle.push.fcm.enabled=true",
                "harudle.push.fcm.project-id=harudle-test"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FirebaseApp.class);
            assertThat(context).hasSingleBean(FirebaseMessaging.class);
            assertThat(context).hasSingleBean(FcmFeedPushSender.class);
            assertThat(context.getBean(FirebaseApp.class).getOptions().getProjectId()).isEqualTo("harudle-test");
        });
    }

    @Test
    @DisplayName("FCM을 켰는데 Firebase 프로젝트 ID가 없으면 서버 시작을 거절한다")
    void rejectsMissingProjectId() {
        contextRunner.withPropertyValues("harudle.push.fcm.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
