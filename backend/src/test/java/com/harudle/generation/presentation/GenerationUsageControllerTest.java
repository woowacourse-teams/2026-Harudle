package com.harudle.generation.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.harudle.auth.presentation.AuthenticatedUserIdResolver;
import com.harudle.common.error.ProblemDetailFactory;
import com.harudle.common.error.TraceIdFilter;
import com.harudle.common.security.ApiAccessDeniedHandler;
import com.harudle.common.security.ApiAuthenticationEntryPoint;
import com.harudle.common.security.ApiProblemResponseWriter;
import com.harudle.common.security.ApiSecurityConfiguration;
import com.harudle.generation.domain.GenerationUsage;
import com.harudle.generation.service.GenerationUsageService;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import io.restassured.module.mockmvc.response.MockMvcResponse;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GenerationUsageController.class)
@Import({
        AuthenticatedUserIdResolver.class,
        ProblemDetailFactory.class,
        TraceIdFilter.class,
        ApiSecurityConfiguration.class,
        ApiAuthenticationEntryPoint.class,
        ApiAccessDeniedHandler.class,
        ApiProblemResponseWriter.class
})
class GenerationUsageControllerTest {

    private static final UUID USER_ID = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");
    private static final LocalDate USAGE_DATE = LocalDate.of(2026, 8, 6);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GenerationUsageService generationUsageService;

    @BeforeEach
    void setUp() {
        RestAssuredMockMvc.mockMvc(mockMvc);
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("오늘 생성 사용량을 조회한다")
    void getTodayUsage() {
        when(generationUsageService.getTodayUsage(USER_ID))
                .thenReturn(new GenerationUsage(USAGE_DATE, 2, 3));

        MockMvcResponse response = RestAssuredMockMvc.given()
                .postProcessors(user(USER_ID.toString()))
                .get("/api/v1/me/generation-usage");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.jsonPath().getString("usageDate")).isEqualTo("2026-08-06");
        assertThat(response.jsonPath().getInt("usedCount")).isEqualTo(2);
        assertThat(response.jsonPath().getInt("limitCount")).isEqualTo(3);
        assertThat(response.jsonPath().getInt("remainingCount")).isEqualTo(1);
    }
}
