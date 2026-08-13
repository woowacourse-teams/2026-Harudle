package com.harudle.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BindingResult;
import org.springframework.validation.DirectFieldBindingResult;

class FieldValidationErrorMapperTest {

    @Test
    @DisplayName("Spring 검증 결과를 API 필드 오류로 변환한다")
    void mapBindingResult() {
        BindingResult bindingResult = new DirectFieldBindingResult(
                new CreateDiaryRequest(),
                "createDiaryRequest"
        );
        bindingResult.rejectValue("sourceText", "NotBlank", "일기 내용은 필수입니다.");

        List<FieldValidationError> errors = FieldValidationErrorMapper.from(bindingResult);

        assertThat(errors).containsExactly(new FieldValidationError(
                "sourceText",
                "일기 내용은 필수입니다."
        ));
    }

    @Test
    @DisplayName("검증 메시지가 없으면 기본 사유로 변환한다")
    void mapNullDefaultMessage() {
        BindingResult bindingResult = new DirectFieldBindingResult(
                new CreateDiaryRequest(),
                "createDiaryRequest"
        );
        bindingResult.rejectValue("sourceText", "NotBlank", null);

        List<FieldValidationError> errors = FieldValidationErrorMapper.from(bindingResult);

        assertThat(errors).containsExactly(new FieldValidationError(
                "sourceText",
                "요청 값이 올바르지 않습니다."
        ));
    }

    private static final class CreateDiaryRequest {

        private String sourceText;
    }
}
