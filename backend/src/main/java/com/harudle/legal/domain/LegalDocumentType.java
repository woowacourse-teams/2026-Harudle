package com.harudle.legal.domain;

import java.util.Optional;

public enum LegalDocumentType {
    TERMS("terms"),
    PRIVACY("privacy");

    private final String path;

    LegalDocumentType(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static Optional<LegalDocumentType> fromPath(String value) {
        if (TERMS.path.equals(value)) return Optional.of(TERMS);
        if (PRIVACY.path.equals(value)) return Optional.of(PRIVACY);
        return Optional.empty();
    }
}
