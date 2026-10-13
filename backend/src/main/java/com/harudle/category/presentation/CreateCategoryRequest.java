package com.harudle.category.presentation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

public record CreateCategoryRequest(
        @NotBlank(message = "카테고리 이름은 필수입니다.") String name,
        @PositiveOrZero(message = "표시 순서는 0 이상이어야 합니다.")
        @JsonDeserialize(using = SortOrderDeserializer.class) Integer sortOrder
) {

    public static final class SortOrderDeserializer extends ValueDeserializer<Integer> {

        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "표시 순서는 정수여야 합니다.");
            }
            return parser.getIntValue();
        }
    }
}
