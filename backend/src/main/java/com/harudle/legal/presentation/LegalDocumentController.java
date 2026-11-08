package com.harudle.legal.presentation;

import com.harudle.legal.application.LegalDocumentCatalog;
import com.harudle.legal.domain.LegalDocument;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Legal")
@RestController
@RequestMapping("/api/v1/legal/documents")
public class LegalDocumentController {
    private final LegalDocumentCatalog catalog;

    public LegalDocumentController(LegalDocumentCatalog catalog) {
        this.catalog = catalog;
    }

    @Operation(summary = "현재 이용약관·개인정보처리방침 열람", description = "비로그인 사용자도 문서 전문·버전·시행일을 확인할 수 있습니다.")
    @GetMapping
    public ResponseEntity<LegalDocumentsResponse> documents() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new LegalDocumentsResponse(catalog.documents()));
    }

    @Operation(summary = "종류와 버전으로 공개 문서 열람", description = "종류는 terms 또는 privacy입니다. 공개되지 않은 종류·버전은 찾을 수 없습니다.")
    @GetMapping("/{type}/{version}")
    public ResponseEntity<LegalDocument> document(@PathVariable String type, @PathVariable String version) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(catalog.find(type, version));
    }
}
