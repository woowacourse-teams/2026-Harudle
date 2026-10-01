package com.harudle.generation.adapter.out.s3;

import com.harudle.generation.diary.service.port.ImageStorageException;

public final class S3ExceptionTranslator {

    public ImageStorageException translate(String operation, String objectKey, Throwable cause, String failureType) {
        return new ImageStorageException(
                "S3 이미지 " + operation + "에 실패했습니다.",
                cause,
                S3MetricFailureType.bounded(failureType)
        );
    }
}
