package com.harudle.support;

import com.harudle.TestcontainersConfiguration;
import com.harudle.auth.application.AccountAccessPolicy;
import com.harudle.auth.application.CurrentUserService;
import com.harudle.auth.application.NicknameChangeService;
import com.harudle.auth.application.OAuthLoginService;
import com.harudle.auth.application.ProfileChangeService;
import com.harudle.auth.application.ProfileImageChangeService;
import com.harudle.auth.application.ProfileImageCleanupService;
import com.harudle.auth.application.ProfileImageCodeGenerator;
import com.harudle.auth.application.ProfileImageProcessor;
import com.harudle.auth.application.ProfileImageUploadService;
import com.harudle.auth.application.RefreshTokenService;
import com.harudle.auth.application.port.AccountRestrictionReader;
import com.harudle.auth.infrastructure.OAuthAccountRepository;
import com.harudle.auth.infrastructure.ProfileImageCleanupRepository;
import com.harudle.auth.infrastructure.S3ProfileImageStorage;
import com.harudle.auth.infrastructure.nickname.NicknameCandidateRepository;
import com.harudle.auth.infrastructure.token.RefreshTokenGenerator;
import com.harudle.auth.infrastructure.token.RefreshTokenHasher;
import com.harudle.common.security.AuthProperties;
import com.harudle.common.security.AuthTimeConfiguration;
import com.harudle.profile.configuration.ProfileConfiguration;
import com.harudle.profile.service.DefaultPublicProfileReader;
import com.harudle.profile.service.ProfileImageUrlResolver;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 회원·인증·프로필 JPA 테스트의 공통 설정이다.
 * 같은 저장소와 서비스를 테스트마다 다른 조합으로 등록하면 Spring 컨텍스트와 DB 컨테이너가 테스트마다 새로 만들어진다.
 * 외부 경계(계정 제한 조회, 프로필 이미지 코드 생성·처리·저장소)는 mock으로 대체하고 나머지는 실제 Bean을 사용한다.
 * 회원 연동 저장소는 실제 Bean을 spy로 감싸므로 스텁하지 않은 테스트에서는 그대로 동작한다.
 * 닉네임 후보 시드 전체를 지우고 다시 만드는 NicknameCandidateRepositoryTest는 같은 DB를 쓰면
 * 다른 테스트의 후보 배정에 영향을 주므로 이 설정을 사용하지 않는다.
 */
@DataJpaTest(showSql = false, properties = "harudle.profile.image-base-url=https://www.harudle.com/profile-images/v1")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnableConfigurationProperties(AuthProperties.class)
@Import({
        TestcontainersConfiguration.class,
        AuthTimeConfiguration.class,
        ProfileConfiguration.class,
        ProfileImageUrlResolver.class,
        DefaultPublicProfileReader.class,
        CurrentUserService.class,
        AccountAccessPolicy.class,
        OAuthLoginService.class,
        NicknameChangeService.class,
        NicknameCandidateRepository.class,
        RefreshTokenService.class,
        RefreshTokenGenerator.class,
        RefreshTokenHasher.class,
        ProfileChangeService.class,
        ProfileImageChangeService.class,
        ProfileImageUploadService.class,
        ProfileImageCleanupService.class,
        ProfileImageCleanupRepository.class
})
public abstract class AuthJpaTestSupport {

    @MockitoBean
    protected AccountRestrictionReader restrictions;

    @MockitoSpyBean
    protected OAuthAccountRepository oAuthAccountRepository;

    @MockitoBean
    protected ProfileImageCodeGenerator profileImageCodeGenerator;

    @MockitoBean
    protected ProfileImageProcessor processor;

    @MockitoBean
    protected S3ProfileImageStorage storage;
}
