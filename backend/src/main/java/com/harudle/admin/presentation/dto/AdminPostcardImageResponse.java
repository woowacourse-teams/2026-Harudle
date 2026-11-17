package com.harudle.admin.presentation.dto;

import java.net.URI;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

public record AdminPostcardImageResponse(URI imageUrl, Instant expiresAt, @Nullable String title) {
}
