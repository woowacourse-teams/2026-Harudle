package com.harudle.generation.diary.service;

import com.harudle.generation.config.OrphanImageCleanupProperties;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.port.GeneratedImageCatalog;
import com.harudle.generation.diary.service.port.GeneratedImageCatalog.Image;
import com.harudle.generation.diary.service.port.ImageStorage;
import com.harudle.generation.prompt.repository.GenerationPromptRepository;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "harudle.generation.adapters", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(OrphanImageCleanupProperties.class)
public final class OrphanImageCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrphanImageCleanupScheduler.class);
    private final GeneratedImageCatalog catalog;
    private final ImageStorage imageStorage;
    private final DiaryGenerationRepository generations;
    private final GenerationPromptRepository prompts;
    private final OrphanImageCleanupProperties properties;
    private final Clock clock;

    public OrphanImageCleanupScheduler(GeneratedImageCatalog catalog, ImageStorage imageStorage,
            DiaryGenerationRepository generations, GenerationPromptRepository prompts,
            OrphanImageCleanupProperties properties, @Qualifier("serviceClock") Clock clock) {
        this.catalog = catalog;
        this.imageStorage = imageStorage;
        this.generations = generations;
        this.prompts = prompts;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${harudle.generation.orphan-image-cleanup.interval:10m}",
            initialDelayString = "${harudle.generation.orphan-image-cleanup.interval:10m}")
    public void cleanup() {
        Instant cutoff = clock.instant().minus(properties.minimumAge());
        String token = null;
        int deleted = 0;
        try {
            // 매번 처음부터 순회해야 삭제 뒤 늦게 완료된 PUT도 다음 실행에서 발견한다.
            do {
                var page = catalog.list(token);
                for (Image image : page.images()) {
                    if (!image.lastModified().isAfter(cutoff) && deleteIfUnused(image)) {
                        deleted++;
                    }
                }
                token = page.nextToken();
            } while (token != null);
        } catch (RuntimeException exception) {
            log.warn("고아 이미지 목록 조회에 실패했습니다. 다음 실행에서 다시 순회합니다.", exception);
        }
        if (deleted > 0) {
            log.info("고아 이미지를 정리했습니다. deletedCount={}", deleted);
        }
    }

    private boolean deleteIfUnused(Image image) {
        try {
            boolean discardable = generations.findById(image.generationId())
                    .map(generation -> switch (generation.getStatus()) {
                        case PROCESSING -> false;
                        case FAILED -> true;
                        case SUCCEEDED -> generation.notUsesImageObjectKey(image.objectKey());
                    }).orElse(true);
            // 최종 상태는 다시 PROCESSING으로 바뀌지 않는다. 다른 생성/프롬프트 참조도 보호한다.
            if (!discardable || generations.existsByImageObjectKey(image.objectKey())
                    || prompts.existsByImageAssetObjectKey(image.objectKey())) {
                return false;
            }
            imageStorage.delete(image.objectKey());
            return true;
        } catch (RuntimeException exception) {
            log.warn("고아 이미지 확인 또는 삭제에 실패해 정리를 보류합니다. objectKey={}",
                    image.objectKey(), exception);
            return false;
        }
    }
}
