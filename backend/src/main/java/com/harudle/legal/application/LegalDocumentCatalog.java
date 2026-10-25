package com.harudle.legal.application;

import com.harudle.legal.domain.LegalDocument;
import com.harudle.legal.domain.LegalDocumentType;
import com.harudle.legal.domain.LegalSection;
import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** 공개 버전은 고정 리소스에서만 읽으며 요청한 경로로 파일을 구성하지 않는다. */
@Component
public class LegalDocumentCatalog {
    private static final List<DocumentSpec> MANIFEST = List.of(
            new DocumentSpec(LegalDocumentType.TERMS, "2026-10-22", "classpath:legal/terms-2026-10-22.json",
                    "ec0c325c8fe651c76e5134b9eeea055613c08de0350398cf09f899f64e479487"),
            new DocumentSpec(LegalDocumentType.PRIVACY, "2026-08-24", "classpath:legal/privacy-2026-08-24.json",
                    "a294f42fa09c856c853439f74c2d71a8e2d32f0100fa4d8505291cfb9231bf4f")
    );
    private static final Map<LegalDocumentType, String> CURRENT_VERSIONS = Map.of(
            LegalDocumentType.TERMS, "2026-10-22",
            LegalDocumentType.PRIVACY, "2026-08-24"
    );
    // 공지성 개정은 이전 수락을 유지하고, 재동의가 필요한 개정은 이전 버전을 이 목록에서 제외한다.
    private static final List<String> ACCEPTED_TERMS_VERSIONS = List.of("2026-10-22");
    private final List<LegalDocument> allDocuments;
    private final Map<LegalDocumentType, String> currentVersions;
    private final List<LegalDocument> currentDocuments;
    private final List<TermsDocumentReference> acceptedTerms;

    @Autowired
    public LegalDocumentCatalog(ObjectMapper mapper, ResourceLoader resources) {
        this(loadDocuments(mapper, resources), CURRENT_VERSIONS, ACCEPTED_TERMS_VERSIONS);
    }

    LegalDocumentCatalog(List<LegalDocument> allDocuments, Map<LegalDocumentType, String> currentVersions) {
        this(allDocuments, currentVersions, List.of(currentVersions.get(LegalDocumentType.TERMS)));
    }

    LegalDocumentCatalog(List<LegalDocument> allDocuments, Map<LegalDocumentType, String> currentVersions,
            List<String> acceptedTermsVersions) {
        this.allDocuments = List.copyOf(allDocuments);
        this.currentVersions = Map.copyOf(currentVersions);
        try {
            this.currentDocuments = this.currentVersions.keySet().stream().sorted().map(this::current).toList();
            String currentTermsVersion = current(LegalDocumentType.TERMS).version();
            if (!acceptedTermsVersions.contains(currentTermsVersion)) {
                throw new IllegalArgumentException("현재 공개 약관의 수락은 항상 유효해야 합니다.");
            }
            this.acceptedTerms = Stream.concat(Stream.of(currentTermsVersion), acceptedTermsVersions.stream())
                    .distinct().map(version -> find(LegalDocumentType.TERMS, version))
                    .map(TermsDocumentReference::from).toList();
        } catch (LegalDocumentNotFoundException exception) {
            throw new IllegalStateException("현재 공개 버전 또는 수락 인정 버전이 배포 문서 목록에 없습니다.", exception);
        }
    }

    public List<LegalDocument> documents() {
        return currentDocuments;
    }

    /** 현재 버전을 먼저 조회하며, 명시적으로 유지한 과거 버전의 수락도 서비스 접근에 인정한다. */
    public List<TermsDocumentReference> acceptedTerms() {
        return acceptedTerms;
    }

    public LegalDocument current(LegalDocumentType type) {
        String version = type == null ? null : currentVersions.get(type);
        if (version == null) throw new LegalDocumentNotFoundException();
        return find(type, version);
    }

    public LegalDocument find(String type, String version) {
        LegalDocumentType documentType = LegalDocumentType.fromPath(type)
                .orElseThrow(LegalDocumentNotFoundException::new);
        return find(documentType, version);
    }

    public LegalDocument find(LegalDocumentType type, String version) {
        return allDocuments.stream().filter(document -> document.type() == type && document.version().equals(version))
                .findFirst().orElseThrow(LegalDocumentNotFoundException::new);
    }

    private static List<LegalDocument> loadDocuments(ObjectMapper mapper, ResourceLoader resources) {
        Objects.requireNonNull(mapper, "문서 JSON 파서는 필수입니다.");
        Objects.requireNonNull(resources, "문서 리소스는 필수입니다.");
        return MANIFEST.stream().map(spec -> load(spec, mapper, resources)).toList();
    }

    private static LegalDocument load(DocumentSpec spec, ObjectMapper mapper, ResourceLoader resources) {
        try (var input = resources.getResource(spec.resource()).getInputStream()) {
            byte[] source = input.readAllBytes();
            String contentHash = hash(source);
            if (!spec.contentHash().equals(contentHash)) {
                throw new IllegalArgumentException("같은 버전의 배포 문서 내용을 변경할 수 없습니다.");
            }
            ResourceDocument parsed = mapper.readerFor(ResourceDocument.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(source);
            if (parsed.type() != spec.type() || !spec.version().equals(parsed.version())) {
                throw new IllegalArgumentException("문서 종류·버전이 배포 목록과 일치하지 않습니다.");
            }
            return new LegalDocument(parsed.type(), parsed.version(), parsed.title(), parsed.effectiveDate(),
                    contentHash, parsed.sourceUrl(), parsed.sections());
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("배포한 약관 문서를 읽을 수 없습니다: " + spec.resource(), exception);
        }
    }

    private static String hash(byte[] source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private record DocumentSpec(LegalDocumentType type, String version, String resource, String contentHash) { }

    private record ResourceDocument(LegalDocumentType type, String version, String title, LocalDate effectiveDate,
                                    URI sourceUrl, List<LegalSection> sections) { }
}
