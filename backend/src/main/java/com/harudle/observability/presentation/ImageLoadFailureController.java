package com.harudle.observability.presentation;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Telemetry")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/v1/telemetry/image-load-failures")
class ImageLoadFailureController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ImageLoadFailureController.class);
    private static final String REPORT_EVENT = "image_load_failure_reported";

    private final MeterRegistry meterRegistry;

    ImageLoadFailureController(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        meterRegistry.counter("harudle.image.load.failures", "surface", "timeline");
        meterRegistry.counter("harudle.image.load.failures", "surface", "detail");
    }

    @Operation(summary = "타임라인 일기 이미지 로드 실패 집계")
    @PostMapping("/timeline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void recordTimelineFailure() {
        record("timeline");
    }

    @Operation(summary = "상세 일기 이미지 로드 실패 집계")
    @PostMapping("/detail")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void recordDetailFailure() {
        record("detail");
    }

    private void record(String surface) {
        meterRegistry.counter("harudle.image.load.failures", "surface", surface).increment();
        LOGGER.atInfo()
                .addKeyValue("event", REPORT_EVENT)
                .addKeyValue("surface", surface)
                .log("event={} surface={}", REPORT_EVENT, surface);
    }
}
