package com.harudle.comment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class CommentContentTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n", "　"})
    void rejectsMissingOrWhitespaceOnlyContent(String content) {
        assertThatIllegalArgumentException().isThrownBy(() -> CommentContent.normalize(content));
    }

    @Test
    void trimsOnlyOuterWhitespaceAndKeepsInteriorText() {
        assertThat(CommentContent.normalize(" \n첫 줄\n둘째 줄 \t")).isEqualTo("첫 줄\n둘째 줄");
    }

    @Test
    void acceptsOneAndOneHundredCodePointsIncludingSupplementaryCharacters() {
        assertThat(CommentContent.normalize("😀")).isEqualTo("😀");
        assertThat(CommentContent.normalize(" \t" + "😀".repeat(100) + "\n "))
                .isEqualTo("😀".repeat(100));
    }

    @Test
    void rejectsOneHundredAndOneCodePoints() {
        assertThatIllegalArgumentException().isThrownBy(() -> CommentContent.normalize("가".repeat(101)));
        assertThatIllegalArgumentException().isThrownBy(() -> CommentContent.normalize("😀".repeat(101)));
    }

    @Test
    void rejectsTheNulCharacterUnsupportedByPostgresqlText() {
        assertThatIllegalArgumentException().isThrownBy(() -> CommentContent.normalize("\0"));
        assertThatIllegalArgumentException().isThrownBy(() -> CommentContent.normalize("앞\0뒤"));
    }

    @Test
    void rejectsUnpairedSurrogatesWithoutChangingValidEmoji() {
        assertThatIllegalArgumentException().isThrownBy(() -> CommentContent.normalize("\uD800"));
        assertThatIllegalArgumentException().isThrownBy(() -> CommentContent.normalize("\uDC00"));
        assertThatIllegalArgumentException().isThrownBy(() -> CommentContent.normalize("앞\uD800뒤"));
        assertThat(CommentContent.normalize("앞😀뒤")).isEqualTo("앞😀뒤");
    }

    @Test
    void keepsMarkupAndDecomposedCharactersAsPlainText() {
        String text = "<script>alert('text')</script> & 가";
        assertThat(CommentContent.normalize(text)).isEqualTo(text);
    }
}
