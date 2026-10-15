package com.harudle.diary.service;

import com.harudle.diary.service.dto.CreateDiaryCommand;
import com.harudle.diary.service.dto.CreateDiaryResult;

public final class LoadTestDiaryCreationService {

    private final DiaryCreationService diaryCreationService;

    LoadTestDiaryCreationService(DiaryCreationService diaryCreationService) {
        this.diaryCreationService = diaryCreationService;
    }

    public CreateDiaryResult create(CreateDiaryCommand command) {
        return diaryCreationService.create(command);
    }
}
