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

    Optional<ReferenceImage> download(String objectKey);

    /** 기존 객체를 덮어쓰지 않는다. ALREADY_EXISTS는 내용 일치나 검증 성공을 의미하지 않는다. */
    BackupUploadResult uploadIfAbsent(String objectKey, GeneratedImage original);

    /** URL 발급은 객체 존재 여부를 확인하지 않는다. 필요한 경우 먼저 findMetadata를 호출한다. */
    ImageAccessUrl createAccessUrl(String objectKey);
}
