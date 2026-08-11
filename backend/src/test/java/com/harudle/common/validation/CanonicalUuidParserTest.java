package com.harudle.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanonicalUuidParserTest {

    private static final UUID UUID_VALUE = UUID.fromString("08d69a34-6d70-4d42-a158-671bc67733c9");

    @Test
    @DisplayName("대소문자와 관계없이 표준 UUID 문자열을 파싱한다")
    void parseCanonicalUuid() {
        assertThat(CanonicalUuidParser.parse(UUID_VALUE.toString().toUpperCase()))
                .contains(UUID_VALUE);
    }

    @Test
    @DisplayName("축약 UUID 문자열은 파싱하지 않는다")
    void rejectAbbreviatedUuid() {
        assertThat(CanonicalUuidParser.parse("1-1-1-1-1")).isEmpty();
    }

    @Test
    @DisplayName("null과 빈 문자열은 파싱하지 않는다")
    void rejectMissingUuid() {
        assertThat(CanonicalUuidParser.parse(null)).isEmpty();
        assertThat(CanonicalUuidParser.parse("  ")).isEmpty();
    }
}
