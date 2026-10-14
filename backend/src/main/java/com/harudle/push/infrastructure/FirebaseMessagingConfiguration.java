package com.harudle.push.infrastructure;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.harudle.feed.configuration.FeedUrlProperties;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "harudle.push.fcm", name = "enabled", havingValue = "true")
class FirebaseMessagingConfiguration {

    @Bean(destroyMethod = "delete")
    FirebaseApp firebaseApp(
            @Value("${harudle.push.fcm.project-id:}") String projectId,
            @Qualifier("firebaseCredentials") ObjectProvider<GoogleCredentials> credentialsProvider
    ) throws IOException {
        if (projectId.isBlank()) {
            throw new IllegalStateException("FCM 활성화 시 FIREBASE_PROJECT_ID가 필요합니다.");
        }
        GoogleCredentials credentials = credentialsProvider.getIfAvailable();
        if (credentials == null) {
            credentials = GoogleCredentials.getApplicationDefault();
        }
        return FirebaseApp.initializeApp(FirebaseOptions.builder()
                .setCredentials(credentials)
                .setProjectId(projectId)
                .build(), "harudle-push");
    }

    @Bean
    FirebaseMessaging firebaseMessaging(FirebaseApp firebaseApp) {
        return FirebaseMessaging.getInstance(firebaseApp);
    }

    @Bean
    FcmFeedPushSender feedPushSender(FirebaseMessaging firebaseMessaging, FeedUrlProperties feedUrls) {
        return new FcmFeedPushSender(firebaseMessaging, feedUrls);
    }
}
