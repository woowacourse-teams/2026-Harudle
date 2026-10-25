package com.harudle.observability.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.observation.ObservationRegistry;
import jakarta.servlet.ServletException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.ServerHttpObservationFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ApiRequestObservationHandlerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(ApiRequestObservationHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ApiRequestObservationHandler handler = new ApiRequestObservationHandler();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(handler);
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .addFilters(new ServerHttpObservationFilter(registry))
                .build();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("완료한 피드 조회는 처리 시간과 고정 구분만 한 번 기록한다")
    void recordsCompletedReadsWithoutRequestData() throws Exception {
        mockMvc.perform(get("/api/v1/feeds").queryParam("cursor", "private-cursor")
                        .header("Authorization", "Bearer private-token"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/feeds/private-feed-id")).andExpect(status().isOk());

        assertThat(appender.list).hasSize(2);
        assertThat(fields(0)).containsEntry("operation", "feed_list").containsEntry("httpStatus", 200)
                .containsEntry("apiGroup", "general");
        assertThat(fields(1)).containsEntry("operation", "feed_detail");
        assertThat(fields(0).keySet()).containsExactlyInAnyOrder(
                "event", "apiGroup", "operation", "httpStatus", "durationMs");
        assertThat((double) fields(0).get("durationMs")).isGreaterThanOrEqualTo(0);
        assertThat(fields(0).toString() + fields(1) + appender.list.getFirst().getFormattedMessage())
                .doesNotContain("private-cursor", "private-token", "private-feed-id");
    }

    @Test
    @DisplayName("일기 생성과 일반 API의 처리 시간을 구분한다")
    void separatesGenerationRequests() throws Exception {
        mockMvc.perform(post("/api/v1/diaries")).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/load-test/diaries")).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/feeds")).andExpect(status().isCreated());

        assertThat(fields(0)).containsEntry("apiGroup", "generation");
        assertThat(fields(1)).containsEntry("apiGroup", "generation");
        assertThat(fields(2)).containsEntry("apiGroup", "general").containsEntry("operation", "other");
    }

    @Test
    @DisplayName("게스트 일기 생성은 생성 API로 기록하고 상세 조회는 일반 API로 기록한다")
    void separatesGuestGenerationFromReads() throws Exception {
        mockMvc.perform(post("/api/v1/guest/diaries")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/guest/diaries/private-diary-id")).andExpect(status().isOk());

        assertThat(appender.list).hasSize(2);
        assertThat(fields(0)).containsEntry("apiGroup", "generation").containsEntry("operation", "other");
        assertThat(fields(1)).containsEntry("apiGroup", "general").containsEntry("operation", "other");
    }

    @Test
    @DisplayName("자동 상태 확인과 지표 수집 요청은 기록하지 않는다")
    void excludesHealthAndManagementRequests() throws Exception {
        mockMvc.perform(get("/api/v1/feeds/00000000-0000-0000-0000-000000000000"));
        mockMvc.perform(get("/actuator/prometheus"));

        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("실패 응답도 최종 상태와 시간을 기록하고 예외 내용은 기록하지 않는다")
    void recordsFailureStatus() throws Exception {
        mockMvc.perform(get("/api/v1/denied")).andExpect(status().isForbidden());
        assertThatThrownBy(() -> mockMvc.perform(get("/api/v1/fail")))
                .isInstanceOf(ServletException.class);

        assertThat(fields(0)).containsEntry("httpStatus", 403);
        assertThat(fields(1)).containsEntry("httpStatus", 500);
        assertThat(fields(1).toString()).doesNotContain("private-error");
    }

    @Test
    @DisplayName("운영 JSON 로그에서 처리 시간과 응답 상태를 숫자로 읽을 수 있다")
    void writesNumericValuesWithTheProductionJsonEncoder() throws Exception {
        LoggerContext encoderContext = new LoggerContext();
        encoderContext.putObject(Environment.class.getName(), new StandardEnvironment());
        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(encoderContext);
        encoder.setFormat("logstash");
        encoder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        OutputStreamAppender<ILoggingEvent> jsonAppender = new OutputStreamAppender<>();
        jsonAppender.setContext(logger.getLoggerContext());
        jsonAppender.setEncoder(encoder);
        jsonAppender.setOutputStream(output);
        jsonAppender.start();
        logger.addAppender(jsonAppender);
        try {
            mockMvc.perform(get("/api/v1/feeds")).andExpect(status().isOk());

            JsonNode event = new ObjectMapper().readTree(output.toString(StandardCharsets.UTF_8));
            assertThat(event.path("event").asString()).isEqualTo("api_request_completed");
            assertThat(event.path("durationMs").isNumber()).isTrue();
            assertThat(event.path("httpStatus").intValue()).isEqualTo(200);
            assertThat(event.path("operation").asString()).isEqualTo("feed_list");
        } finally {
            logger.detachAppender(jsonAppender);
            jsonAppender.stop();
            encoderContext.stop();
        }
    }

    @Test
    @DisplayName("완료 처리가 반복되어도 한 요청을 두 번 기록하지 않는다")
    void doesNotRecordTheSameObservationTwice() {
        ServerRequestObservationContext context = new ServerRequestObservationContext(
                new MockHttpServletRequest("GET", "/api/v1/feeds"), new MockHttpServletResponse());
        handler.onStart(context);
        handler.onStop(context);
        handler.onStop(context);

        assertThat(appender.list).hasSize(1);
    }

    private Map<String, Object> fields(int index) {
        return appender.list.get(index).getKeyValuePairs().stream()
                .collect(Collectors.toMap(pair -> pair.key, pair -> pair.value));
    }

    @RestController
    static class TestController {

        @GetMapping({"/api/v1/feeds", "/api/v1/feeds/{feedId}", "/api/v1/guest/diaries/{diaryId}"})
        String read() {
            return "ok";
        }

        @PostMapping({"/api/v1/diaries", "/api/v1/guest/diaries", "/api/v1/load-test/diaries", "/api/v1/feeds"})
        ResponseEntity<Void> create() {
            return ResponseEntity.status(HttpStatus.CREATED).build();
        }

        @GetMapping("/api/v1/denied")
        ResponseEntity<Void> denied() {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        @GetMapping("/api/v1/fail")
        void fail() {
            throw new IllegalStateException("private-error");
        }
    }
}
