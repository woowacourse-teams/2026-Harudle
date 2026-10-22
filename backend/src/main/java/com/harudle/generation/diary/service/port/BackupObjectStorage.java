package com.harudle.generation.diary.service.port;

import com.harudle.generation.diary.service.port.dto.BackupObjectMetadata;
import com.harudle.generation.diary.service.port.dto.BackupUploadResult;
import com.harudle.generation.diary.service.port.dto.GeneratedImage;
import com.harudle.generation.diary.service.port.dto.ImageAccessUrl;
import com.harudle.generation.diary.service.port.dto.ReferenceImage;
import java.util.Optional;

/** 현재 환경의 이미지 원본을 동일한 Object Key로 보관하는 백업 저장소. */
public interface BackupObjectStorage {

    /** 파일이 없을 때만 empty를 반환하며, 권한과 통신 오류는 예외로 전달한다. */
    Optional<BackupObjectMetadata> findMetadata(String objectKey);

    Optional<BackupObjectMetadata> findMetadata(String objectKey, ImageLookupBudget budget);

    Optional<ReferenceImage> download(String objectKey);

    /** 기존 객체를 덮어쓰지 않는다. ALREADY_EXISTS는 내용 일치나 검증 성공을 의미하지 않는다. */
    BackupUploadResult uploadIfAbsent(String objectKey, GeneratedImage original);

    /** 원본과 화면 표시용 이미지의 URL을 존재 확인 없이 발급한다. 백업 작업은 원본만 처리한다. */
    ImageAccessUrl createAccessUrl(String objectKey);
}
