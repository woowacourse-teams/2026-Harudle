package com.harudle.feed.configuration;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

/** 공개 공유 페이지와 이미지 주소만 생성하며 개인 일기 URL 발급에는 관여하지 않는다. */
public final class FeedPreviewUrls {

    public static final String BASE_PATH = "/api/v1/public/feeds";

    private final FeedUrlProperties feeds;
    private final URI publicOrigin;
    private final String imageBaseUrl;

    public FeedPreviewUrls(FeedUrlProperties feeds, FeedPreviewProperties previews) {
        this.feeds = Objects.requireNonNull(feeds);
        URI base = feeds.publicBaseUrl();
        this.publicOrigin = URI.create(base.getScheme() + "://" + base.getRawAuthority());
        String configured = previews.imageBaseUrl();
        this.imageBaseUrl = configured == null || configured.isBlank()
                ? publicOrigin.resolve(BASE_PATH).toString() : configured.replaceAll("/+$", "");
    }

    public URI shareUrl(UUID feedId) {
        Objects.requireNonNull(feedId, "피드 ID는 필수입니다.");
        return feeds.shareUrl(feedId);
    }

    public URI imageUrl(UUID feedId) {
        Objects.requireNonNull(feedId, "피드 ID는 필수입니다.");
        return URI.create(imageBaseUrl + "/" + feedId + "/image");
    }

}
