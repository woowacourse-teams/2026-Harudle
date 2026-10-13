package com.harudle.comment.service.dto;

import com.harudle.profile.service.port.PublicProfileReader.Profile;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** 탈퇴가 확인된 작성자의 공개 프로필만 null로 표현한다. 댓글과 내부 작성자 ID는 유지한다. */
public record CommentResult(UUID id, @Nullable Profile author, String content, Instant createdAt, boolean isMine) {
    public CommentResult {
        Objects.requireNonNull(id);
        Objects.requireNonNull(content);
        Objects.requireNonNull(createdAt);
        if (author == null && isMine) {
            throw new IllegalArgumentException("탈퇴한 회원의 댓글은 본인 댓글로 표시할 수 없습니다.");
        }
    }
}
