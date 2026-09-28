package com.harudle.generation.diary.domain;

import com.harudle.common.validation.TextValidator;
import java.util.List;
import java.util.regex.Pattern;

public record FocalColor(
        String prop,
        String component,
        String colorHex,
        List<Integer> panelNumbers
) {

    private static final Pattern HEX_COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");

    public FocalColor {
        prop = TextValidator.normalizeRequired(prop, "색상 대상 소품은 필수입니다.");
        component = TextValidator.normalizeRequired(component, "색칠할 소품 부분은 필수입니다.");
        colorHex = TextValidator.normalizeRequired(colorHex, "색상 코드는 필수입니다.");
        if (!HEX_COLOR.matcher(colorHex).matches()) {
            throw new IllegalArgumentException("색상 코드는 #RRGGBB 형식이어야 합니다.");
        }
        if (panelNumbers == null || panelNumbers.isEmpty()) {
            throw new IllegalArgumentException("색상 대상의 패널 번호는 필수입니다.");
        }
        panelNumbers = List.copyOf(panelNumbers);
        for (int index = 0; index < panelNumbers.size(); index++) {
            int panelNumber = panelNumbers.get(index);
            if (panelNumber < 1 || panelNumber > Storyboard.PANEL_COUNT
                    || (index > 0 && panelNumber <= panelNumbers.get(index - 1))) {
                throw new IllegalArgumentException("색상 대상의 패널 번호는 1부터 4까지 중복 없이 순서대로 지정해야 합니다.");
            }
        }
    }
}
