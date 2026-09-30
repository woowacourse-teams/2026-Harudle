package com.harudle.observability.presentation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
}
