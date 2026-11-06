package com.harudle.support;

import com.harudle.TestcontainersConfiguration;
import com.harudle.profile.service.DefaultPublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 피드 좋아요·댓글·알림처럼 실제 상호작용 서비스와 MockMvc를 함께 사용하는 영속성 테스트의 공통 설정이다.
 * 푸시 발송 예약은 mock으로 대체하고, 프로필 조회는 실제 Bean을 spy로 감싸 필요한 테스트만 응답을 바꾼다.
 */
@SpringBootTest(properties = {
        "harudle.generation.adapters.enabled=false",
        "harudle.profile.image-base-url=https://test.example.com/profiles"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public abstract class FeedInteractionPersistenceTestSupport {

    @MockitoSpyBean
    protected DefaultPublicProfileReader profileReader;

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @MockitoBean
    protected FeedPushOutbox outbox;
}
