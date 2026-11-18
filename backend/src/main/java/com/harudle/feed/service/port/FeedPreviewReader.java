package com.harudle.feed.service.port;

import com.harudle.feed.service.dto.FeedPreviewDetails;
import java.util.UUID;

/** 현재 익명 공개 범위를 확인하고 미리보기에 필요한 정보만 읽는다. */
@FunctionalInterface
public interface FeedPreviewReader {

    FeedPreviewDetails read(UUID feedId);
}
