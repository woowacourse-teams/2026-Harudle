package com.harudle.legal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.harudle.legal.domain.LegalDocument;
import com.harudle.legal.domain.LegalDocumentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import tools.jackson.databind.ObjectMapper;

class LegalDocumentCatalogTest {
    private static final String TERMS_HASH = "ec0c325c8fe651c76e5134b9eeea055613c08de0350398cf09f899f64e479487";
    private static final String PRIVACY_HASH = "a294f42fa09c856c853439f74c2d71a8e2d32f0100fa4d8505291cfb9231bf4f";

    @Test
    @DisplayName("실제 공개 문서 원문과 고정 해시를 읽고 종류·버전으로 같은 스냅샷을 제공한다")
    void loadsPublishedDocumentsWithPinnedHashes() {
        LegalDocumentCatalog catalog = catalog();
        assertThat(catalog.documents()).extracting(document -> document.type())
                .containsExactly(LegalDocumentType.TERMS, LegalDocumentType.PRIVACY);
        var terms = catalog.find("terms", "2026-10-22");
        assertThat(terms).isSameAs(catalog.current(LegalDocumentType.TERMS));
        assertThat(terms.contentHash()).isEqualTo(TERMS_HASH);
        assertThat(terms.title()).isEqualTo("하루들 서비스 이용약관");
        assertThat(terms.effectiveDate()).hasToString("2026-10-22");
        assertThat(terms.sections()).isNotEmpty();
        assertThat(catalog.current(LegalDocumentType.PRIVACY).contentHash()).isEqualTo(PRIVACY_HASH);
    }

    @ParameterizedTest
    @CsvSource({
            "TERMS,2026-10-22",
            "unknown,2026-10-22",
            "terms,2026-10-23",
            "../terms,2026-10-22",
            "terms,../../application.yml"
    })
    @DisplayName("공개하지 않은 종류·버전과 경로 입력으로 추가 리소스를 읽지 않는다")
    void neverBuildsAResourcePathFromRequest(String type, String version) {
        AtomicInteger reads = new AtomicInteger();
        var resources = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                reads.incrementAndGet();
                return super.getResource(location);
            }
        };
        LegalDocumentCatalog catalog = new LegalDocumentCatalog(new ObjectMapper(), resources);
        assertThatThrownBy(() -> catalog.find(type, version)).isInstanceOf(LegalDocumentNotFoundException.class);
        assertThat(reads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 공개 버전의 원문 바이트가 바뀌면 서버 시작을 거절한다")
    void failsWhenPublishedBytesChange() {
        var resources = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                Resource resource = super.getResource(location);
                if (!location.contains("terms-")) return resource;
                try (var input = resource.getInputStream()) {
                    String changed = new String(input.readAllBytes(), StandardCharsets.UTF_8) + "\n";
                    return new ByteArrayResource(changed.getBytes(StandardCharsets.UTF_8));
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
        assertThatThrownBy(() -> new LegalDocumentCatalog(new ObjectMapper(), resources))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("필수 문서 리소스가 빠지면 빈 문서로 대신하지 않고 서버 시작을 거절한다")
    void failsWhenPublishedResourceIsMissing() {
        var resources = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return super.getResource("classpath:legal/not-published.json");
            }
        };
        assertThatThrownBy(() -> new LegalDocumentCatalog(new ObjectMapper(), resources))
                .isInstanceOf(IllegalStateException.class).hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("반환된 문서 목록과 중첩 문단을 호출자가 수정할 수 없다")
    void keepsNestedContentsImmutable() {
        LegalDocumentCatalog catalog = catalog();
        var terms = catalog.current(LegalDocumentType.TERMS);
        assertThatThrownBy(() -> catalog.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> terms.sections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> terms.sections().getFirst().paragraphs().add("다른 문장"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("과거 버전을 먼저 보관해도 명시한 현재 버전만 목록에 내보내고 과거 전문은 계속 조회한다")
    void separatesCurrentVersionsFromRetainedHistory() {
        LegalDocumentCatalog published = catalog();
        var terms = published.current(LegalDocumentType.TERMS);
        var privacy = published.current(LegalDocumentType.PRIVACY);
        var oldTerms = new LegalDocument(LegalDocumentType.TERMS, "2026-10-21", "이전 이용약관",
                LocalDate.of(2026, 10, 21), "1".repeat(64), terms.sourceUrl(), terms.sections());
        var catalog = new LegalDocumentCatalog(List.of(oldTerms, terms, privacy), Map.of(
                LegalDocumentType.TERMS, terms.version(), LegalDocumentType.PRIVACY, privacy.version()));

        assertThat(catalog.documents()).containsExactly(terms, privacy);
        assertThat(catalog.current(LegalDocumentType.TERMS)).isSameAs(terms);
        assertThat(catalog.find("terms", "2026-10-21")).isSameAs(oldTerms);
    }

    @Test
    @DisplayName("현재 버전 참조가 배포 문서 목록에 없으면 시작 단계에서 거절한다")
    void rejectsCurrentVersionMissingFromManifest() {
        LegalDocumentCatalog published = catalog();
        assertThatThrownBy(() -> new LegalDocumentCatalog(published.documents(), Map.of(
                LegalDocumentType.TERMS, "2026-10-23", LegalDocumentType.PRIVACY, "2026-08-24")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("현재 공개 버전")
                .hasCauseInstanceOf(LegalDocumentNotFoundException.class);
    }

    private LegalDocumentCatalog catalog() {
        return new LegalDocumentCatalog(new ObjectMapper(), new DefaultResourceLoader());
    }
}
