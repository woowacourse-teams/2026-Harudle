package com.harudle.support;

import com.harudle.HarudleApplication;
import com.harudle.TestcontainersConfiguration;
import com.harudle.generation.diary.service.DiaryGenerationWorker;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = HarudleApplication.class)
@Import(TestcontainersConfiguration.class)
public abstract class ServiceIntegrationTestSupport {

    @MockitoBean
    protected JwtDecoder jwtDecoder;

    @MockitoBean(enforceOverride = true)
    private DiaryGenerationWorker generationWorker;
}
