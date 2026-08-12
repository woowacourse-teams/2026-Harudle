package com.harudle.share.controller;

import com.harudle.generation.service.port.ImageAccessUrl;
import com.harudle.generation.service.port.ImageUrlProvider;
import com.harudle.share.controller.dto.PublicShareResponse;
import com.harudle.share.service.PublicShareResult;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

@Component
class PublicShareResponseAssembler {

    private final ImageUrlProvider imageUrlProvider;
    private final ZoneId serviceZoneId;

    PublicShareResponseAssembler(ImageUrlProvider imageUrlProvider, ZoneId serviceZoneId) {
        this.imageUrlProvider = imageUrlProvider;
        this.serviceZoneId = serviceZoneId;
    }

    PublicShareResponse toResponse(PublicShareResult result) {
        ImageAccessUrl imageAccessUrl = imageUrlProvider.createAccessUrl(result.imageObjectKey());
        return new PublicShareResponse(
                result.title(),
                result.diaryDate(),
                imageAccessUrl.url(),
                toServiceTime(imageAccessUrl.expiresAt()),
                toServiceTime(result.createdAt())
        );
    }

    private OffsetDateTime toServiceTime(Instant instant) {
        return instant.atZone(serviceZoneId).toOffsetDateTime();
    }
}
