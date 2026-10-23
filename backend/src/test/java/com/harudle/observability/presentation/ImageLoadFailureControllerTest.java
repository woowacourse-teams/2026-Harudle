package com.harudle.observability.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ImageLoadFailureControllerTest {

    @Test
    @DisplayName("일기 이미지 로드 실패는 고정된 화면 구분만 지표에 남긴다")
    void recordsOnlyFixedSurfaceTags() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ImageLoadFailureController(registry)).build();

        assertThat(registry.get("harudle.image.load.failures")
                .tag("surface", "timeline").counter().count()).isZero();
        assertThat(registry.get("harudle.image.load.failures")
                .tag("surface", "detail").counter().count()).isZero();

        mockMvc.perform(post("/api/v1/telemetry/image-load-failures/timeline"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/telemetry/image-load-failures/detail"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/telemetry/image-load-failures/other"))
                .andExpect(status().isNotFound());

        assertThat(registry.get("harudle.image.load.failures")
                .tag("surface", "timeline").counter().count()).isEqualTo(1);
        assertThat(registry.get("harudle.image.load.failures")
                .tag("surface", "detail").counter().count()).isEqualTo(1);
        assertThat(registry.find("harudle.image.load.failures").meters()).hasSize(2);
    }

    @Test
    @DisplayName("수락한 이미지 실패 신고는 화면 구분만 구조화 로그에 기록한다")
    void logsAcceptedReportsWithoutRequestData() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ImageLoadFailureController(registry)).build();
        Logger logger = (Logger) LoggerFactory.getLogger(ImageLoadFailureController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            mockMvc.perform(post("/api/v1/telemetry/image-load-failures/timeline")
                            .param("imageUrl", "https://s3.example/private-object"))
                    .andExpect(status().isNoContent());
            mockMvc.perform(post("/api/v1/telemetry/image-load-failures/detail"))
                    .andExpect(status().isNoContent());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        "event=image_load_failure_reported surface=timeline",
                        "event=image_load_failure_reported surface=detail"
                );
        assertThat(appender.list)
                .extracting(event -> event.getKeyValuePairs().stream()
                        .map(pair -> pair.key + "=" + pair.value)
                        .toList())
                .containsExactly(
                        List.of("event=image_load_failure_reported", "surface=timeline"),
                        List.of("event=image_load_failure_reported", "surface=detail")
                );
    }
}
