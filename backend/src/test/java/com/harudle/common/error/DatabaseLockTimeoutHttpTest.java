package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.restassured.module.mockmvc.RestAssuredMockMvc;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class DatabaseLockTimeoutHttpTest {
    @BeforeEach
    void setUp() {
        var handler = new GlobalExceptionHandler(new ProblemDetailFactory(new RequestTraceId(() -> "lock-test")),
                mock(ApiExceptionLogger.class));
        RestAssuredMockMvc.mockMvc(MockMvcBuilders.standaloneSetup(new LockProbeController())
                .setControllerAdvice(handler).build());
    }

    @AfterEach
    void tearDown() {
        RestAssuredMockMvc.reset();
    }

    @Test
    @DisplayName("잠금 대기 초과만 내부 SQL을 노출하지 않는 재시도 가능한 503으로 응답한다")
    void returnsRetryableErrorForLockTimeout() {
        var response = RestAssuredMockMvc.given().param("state", "55P03").get("/api/v1/test/locks");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.jsonPath().getString("code")).isEqualTo("DATABASE_LOCK_TIMEOUT");
        assertThat(response.jsonPath().getString("detail")).isEqualTo(ErrorType.DATABASE_LOCK_TIMEOUT.detail());
        assertThat(response.jsonPath().getString("traceId")).isEqualTo("lock-test");
        assertThat(response.asString()).doesNotContain("private SQL");
    }

    @ParameterizedTest
    @ValueSource(strings = {"57014", "40P01", "08006"})
    @DisplayName("다른 SQL 실패를 잠금 대기 초과로 잘못 안내하지 않는다")
    void doesNotReclassifyOtherDatabaseFailures(String sqlState) {
        var response = RestAssuredMockMvc.given().param("state", sqlState).get("/api/v1/test/locks");
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.jsonPath().getString("code")).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @RestController
    static class LockProbeController {
        @GetMapping("/api/v1/test/locks")
        void fail(@RequestParam String state) {
            throw new CannotAcquireLockException("private SQL", new SQLException("private SQL", state));
        }
    }
}
