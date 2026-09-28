package com.harudle.generation.diary.domain;

import com.harudle.common.validation.TextValidator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public record ScenePlan(String environmentRule, List<FocalColor> focalColors) {

    public ScenePlan {
        environmentRule = TextValidator.normalizeRequired(environmentRule, "장면 배경 규칙은 필수입니다.");
        if (focalColors == null) {
            throw new IllegalArgumentException("색상 계획 목록은 필수입니다.");
        }
        focalColors = List.copyOf(focalColors);
        Set<String> targets = new HashSet<>();
        for (FocalColor focalColor : focalColors) {
            String target = focalColor.prop().toLowerCase(Locale.ROOT)
                    + "\u0000" + focalColor.component().toLowerCase(Locale.ROOT);
            if (!targets.add(target)) {
                throw new IllegalArgumentException("같은 소품 부분에 색상을 중복 지정할 수 없습니다.");
            }
        }
    }
}
