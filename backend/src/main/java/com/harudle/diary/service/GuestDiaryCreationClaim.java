package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;

record GuestDiaryCreationClaim(
        CreateDiaryCommand command,
        DiaryCreationClaim claim
) {
}
