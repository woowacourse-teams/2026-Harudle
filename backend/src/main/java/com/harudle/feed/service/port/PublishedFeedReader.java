package com.harudle.feed.service.port;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 개인 일기에 연결된 피드 ID(publishedFeedId)를 조회한다. */
public interface PublishedFeedReader {

    /**
     * 일기 ID를 키로, 연결된 피드 ID를 값으로 반환한다. 삭제된 일기와 피드는 제외한다.
     * 게시되지 않은 일기는 결과에서 빼고, 응답을 만드는 쪽에서 publishedFeedId를 null로 표시한다.
     * 입력은 null을 허용하지 않는다. 빈 Set을 받거나 결과가 없으면 빈 Map을 반환한다.
     * 해당 일기를 조회할 권한이 있는지는 이 메서드를 호출하기 전에 확인한다.
     */
    Map<UUID, UUID> findByDiaryIds(Set<UUID> diaryIds);
}
