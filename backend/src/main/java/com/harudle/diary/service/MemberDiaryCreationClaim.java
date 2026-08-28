package com.harudle.diary.service;

import com.harudle.generation.domain.GenerationUsage;

record MemberDiaryCreationClaim(
        DiaryCreationClaim claim,
        GenerationUsage usage
) {
}
