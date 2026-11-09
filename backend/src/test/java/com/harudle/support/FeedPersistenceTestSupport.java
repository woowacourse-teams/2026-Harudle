package com.harudle.support;

import com.harudle.TestcontainersConfiguration;
import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.service.port.FeedLikeReader;
import com.harudle.profile.service.port.PublicProfileReader;
import com.harudle.push.service.port.FeedPushOutbox;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 피드 게시·조회·삭제 영속성 테스트의 공통 설정이다.
 * 카테고리·프로필·좋아요 조회와 푸시 발송 예약은 mock으로 대체하고 피드 저장소와 서비스는 실제 Bean을 사용한다.
 * 스텁하지 않은 좋아요 조회는 빈 결과를 반환하므로 좋아요가 없는 상태로 동작한다.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class FeedPersistenceTestSupport {

    @MockitoBean
    protected CategoryReader categories;

    @MockitoBean
    protected PublicProfileReader profiles;

    @MockitoBean
    protected FeedLikeReader likes;

    @MockitoBean
    protected FeedPushOutbox outbox;

    @MockitoBean
    protected JwtDecoder jwtDecoder;
}
