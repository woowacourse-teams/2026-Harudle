package com.harudle.diary.service;

import com.harudle.generation.usage.domain.GenerationUsage;

record MemberDiaryCreationClaim(
        DiaryCreationClaim claim,
        GenerationUsage usage
) {
}
