package com.harudle.comment.presentation;

import com.harudle.profile.service.port.PublicProfileReader.Profile;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record CommentAuthorResponse(
        @Schema(description = "작성자 ID. 탈퇴한 회원은 null", type = "string", format = "uuid", nullable = true)
        @Nullable UUID id,
        @Schema(description = "표시 닉네임. 탈퇴한 회원은 '탈퇴한 회원'") String nickname,
        @Schema(description = "프로필 이미지 URL. 탈퇴한 회원은 null이며 화면에서 기본 아이콘을 표시",
                type = "string", format = "uri", nullable = true) @Nullable URI profileImageUrl,
        @Schema(description = "개인 프로필 이미지 URL 만료 시각. 기본 이미지 또는 탈퇴 회원이면 null",
                type = "string", format = "date-time", nullable = true)
        @Nullable Instant profileImageUrlExpiresAt
) {
    static CommentAuthorResponse from(@Nullable Profile profile) {
        if (profile == null) {
            return new CommentAuthorResponse(null, "탈퇴한 회원", null, null);
        }
        return new CommentAuthorResponse(
                profile.id(), profile.nickname(), profile.profileImageUrl(), profile.profileImageUrlExpiresAt()
        );
    }
}
