package com.harudle.auth.domain;

import java.util.Objects;

public record WithdrawalImageDeletion(Storage storage, String objectKey) {
    public WithdrawalImageDeletion {
        Objects.requireNonNull(storage, "저장소는 필수입니다.");
        if (objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("삭제할 이미지 경로는 필수입니다.");
        }
    }

    @Override
    public String toString() {
        return "WithdrawalImageDeletion[storage=" + storage + "]";
    }

    public enum Storage { S3_DIARY, S3_PROFILE, R2 }
}
