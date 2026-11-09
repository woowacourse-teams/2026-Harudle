package com.harudle.diary.service;

import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Compares the resolved Spring write policy with the artifact-selected OS environment mode.
 * Reading the mode directly prevents higher-priority JVM or JSON properties from changing both sides of the check.
 */
public final class DiarySourceTextWritePolicyValidator {

    private static final String RELEASE_MODE_ENVIRONMENT_VARIABLE = "DIARY_SOURCE_TEXT_RELEASE_MODE";

    private DiarySourceTextWritePolicyValidator() {
    }

    public static void validate(DiarySourceTextWriteProperties properties, ConfigurableEnvironment environment) {
        Object mode = environment.getSystemEnvironment().get(RELEASE_MODE_ENVIRONMENT_VARIABLE);
        if (mode == null && !environment.matchesProfiles("prod")) {
            return;
        }
        if (!("reader".equals(mode) || "writer".equals(mode))
                || properties.newWritesEnabled() != "writer".equals(mode)) {
            throw new IllegalStateException("Diary source text write policy validation failed.");
        }
    }
}
