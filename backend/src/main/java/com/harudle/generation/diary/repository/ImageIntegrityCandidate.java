package com.harudle.generation.diary.repository;

import java.util.UUID;

/** A successful generation whose image is still visible to a user. */
public record ImageIntegrityCandidate(UUID generationId, String imageObjectKey) {
}
