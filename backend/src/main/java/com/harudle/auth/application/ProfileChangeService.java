package com.harudle.auth.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProfileChangeService {

    private final NicknameChangeService nicknameChangeService;
    private final ProfileImageChangeService imageChangeService;

    public ProfileChangeService(
            NicknameChangeService nicknameChangeService,
            ProfileImageChangeService imageChangeService
    ) {
        this.nicknameChangeService = nicknameChangeService;
        this.imageChangeService = imageChangeService;
    }

    @Transactional
    public @Nullable String change(
            UUID userId,
            @Nullable String nickname,
            @Nullable Integer imageCode,
            @Nullable String objectKey
    ) {
        if (nickname != null) {
            nicknameChangeService.change(userId, nickname);
        }
        if (imageCode != null) {
            return imageChangeService.changeDefault(userId, imageCode);
        }
        if (objectKey != null) {
            return imageChangeService.changeUploaded(userId, objectKey);
        }
        return null;
    }
}
