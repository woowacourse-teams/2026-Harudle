package com.harudle.legal.presentation;

import com.harudle.legal.domain.LegalDocument;
import java.util.List;

public record LegalDocumentsResponse(List<LegalDocument> documents) {
    public LegalDocumentsResponse {
        documents = List.copyOf(documents);
    }
}
