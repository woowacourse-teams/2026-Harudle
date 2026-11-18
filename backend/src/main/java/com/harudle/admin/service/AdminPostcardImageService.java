package com.harudle.admin.service;

import com.harudle.admin.query.AdminPostcardImage;
import com.harudle.diary.repository.DiaryRepository;
import com.harudle.generation.diary.domain.DiaryGeneration;
import com.harudle.generation.diary.domain.GenerationStatus;
import com.harudle.generation.diary.domain.ImageVariantKeys;
import com.harudle.generation.diary.repository.DiaryGenerationRepository;
import com.harudle.generation.diary.service.exception.DiaryGenerationNotFoundException;
import com.harudle.generation.diary.service.port.ImageStorage;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 관리자 엽서 인쇄용 이미지를 고른다. 인쇄 화질을 위해 최적화 상세 이미지 대신 원본을 우선한다.
 * 저장소 존재 확인은 외부 호출이라 DB 트랜잭션 밖에서 한다.
 */
@Service
public class AdminPostcardImageService {

    private final DiaryGenerationRepository generations;
    private final DiaryRepository diaries;
    private final ObjectProvider<ImageStorage> storages;

    public AdminPostcardImageService(DiaryGenerationRepository generations, DiaryRepository diaries,
            ObjectProvider<ImageStorage> storages) {
        this.generations = generations;
        this.diaries = diaries;
        this.storages = storages;
    }

    /** 생성에 성공했고 일기가 삭제되지 않은 경우에만 돌려준다. 그 밖에는 생성이 없는 것으로 다룬다. */
    public AdminPostcardImage find(UUID generationId) {
        DiaryGeneration generation = generations.findById(generationId)
                .orElseThrow(DiaryGenerationNotFoundException::new);
        String detailKey = generation.getImageObjectKey();
        if (generation.getStatus() != GenerationStatus.SUCCEEDED || detailKey == null || detailKey.isBlank()) {
            throw new DiaryGenerationNotFoundException();
        }
        boolean diaryAvailable = diaries.findById(generation.getDiaryId())
                .map(diary -> !diary.isDeleted())
                .orElse(false);
        if (!diaryAvailable) {
            throw new DiaryGenerationNotFoundException();
        }
        return new AdminPostcardImage(printableKey(detailKey), generation.getTitle());
    }

    private String printableKey(String detailKey) {
        ImageStorage storage = storages.getIfAvailable();
        if (storage == null) {
            return detailKey;
        }
        for (String originalKey : ImageVariantKeys.originalImageKeyCandidates(detailKey)) {
            if (storage.exists(originalKey)) {
                return originalKey;
            }
        }
        return detailKey;
    }
}
