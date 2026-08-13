package com.harudle.share.controller;

import com.harudle.share.configuration.ShareUrlProperties;
import com.harudle.share.controller.dto.ShareLinkResponse;
import com.harudle.share.service.ShareLinkCreationResult;
import java.net.URI;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
class ShareLinkResponseAssembler {

    private final ShareUrlProperties shareUrlProperties;
    private final ZoneId serviceZoneId;

    ShareLinkResponseAssembler(ShareUrlProperties shareUrlProperties, ZoneId serviceZoneId) {
        this.shareUrlProperties = shareUrlProperties;
        this.serviceZoneId = serviceZoneId;
    }

    ShareLinkResponse toResponse(ShareLinkCreationResult result) {
        URI shareUrl = UriComponentsBuilder.fromUri(shareUrlProperties.publicBaseUrl())
                .pathSegment(result.shareId().toString())
                .build()
                .toUri();
        return new ShareLinkResponse(
                result.shareId(),
                shareUrl,
                result.createdAt().atZone(serviceZoneId).toOffsetDateTime()
        );
    }
}
