package com.harudle.feed.service;

import com.harudle.category.service.port.CategoryReader;
import com.harudle.feed.query.FeedCursor;
import com.harudle.feed.query.FeedSort;
import com.harudle.feed.repository.FeedListRepository;
import com.harudle.feed.repository.FeedSnapshot;
import com.harudle.feed.service.dto.FeedPageResult;
import com.harudle.feed.service.dto.FeedResult;
import com.harudle.profile.service.port.PublicProfileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class FeedListService {
    private final FeedListRepository feeds;
    private final FeedCollaborators collaborators;
    private final FeedCursorCodec cursors;

    public FeedListService(FeedListRepository feeds, FeedCollaborators collaborators, FeedCursorCodec cursors) {
        this.feeds = feeds;
        this.collaborators = collaborators;
        this.cursors = cursors;
    }

    public FeedPageResult getList(
            @Nullable UUID viewerId, FeedSort sort, @Nullable Long categoryId, @Nullable String cursor, int size
    ) {
        Objects.requireNonNull(sort);
        if (size < 1 || size > 50 || (categoryId != null && categoryId <= 0)) {
            throw new IllegalArgumentException("목록 조회 조건이 올바르지 않습니다.");
        }
        FeedCursor boundary = cursors.decode(cursor, sort, categoryId);
        Map<Long, CategoryReader.Category> categories = new HashMap<>();
        if (categoryId != null) {
            categories.put(categoryId, collaborators.categories().get(categoryId));
        }
        var candidates = new ArrayList<FeedSnapshot>(size + 1);
        Map<UUID, PublicProfileReader.Profile> profiles = new HashMap<>();
        Set<UUID> resolvedAuthors = new HashSet<>();
        Set<UUID> scannedFeeds = new HashSet<>();
        while (candidates.size() <= size) {
            List<FeedSnapshot> batch = feeds.findAfter(sort, categoryId, boundary, size + 1);
            if (batch.isEmpty()) {
                break;
            }
            Set<UUID> authors = batch.stream().map(FeedSnapshot::authorId)
                    .filter(author -> !resolvedAuthors.contains(author)).collect(Collectors.toSet());
            if (!authors.isEmpty()) {
                profiles.putAll(collaborators.profiles().readAll(authors));
                resolvedAuthors.addAll(authors);
            }
            for (FeedSnapshot feed : batch) {
                if (scannedFeeds.add(feed.id()) && profiles.containsKey(feed.authorId())) {
                    candidates.add(feed);
                    if (candidates.size() > size) {
                        break;
                    }
                }
            }
            if (candidates.size() > size || batch.size() < size + 1) {
                break;
            }
            // 조회 중 프로필이 사라진 항목은 건너뛰고, 읽은 DB 경계 뒤에서 페이지를 보충한다.
            boundary = FeedCursor.after(sort, categoryId, batch.getLast());
        }
        boolean hasNext = candidates.size() > size;
        List<FeedSnapshot> page = candidates.subList(0, Math.min(size, candidates.size()));
        Set<UUID> feedIds = page.stream().map(FeedSnapshot::id).collect(Collectors.toSet());
        Set<UUID> liked = viewerId == null || page.isEmpty() ? Set.of()
                : collaborators.likes().findLikedFeedIds(viewerId, feedIds);
        List<FeedResult> items = page.stream().map(feed -> new FeedResult(
                feed.id(), profiles.get(feed.authorId()),
                categories.computeIfAbsent(feed.categoryId(), id -> collaborators.categories().get(id)),
                feed.imageObjectKey(), feed.publishedAt(), feed.likeCount(), feed.commentCount(),
                liked.contains(feed.id()), feed.authorId().equals(viewerId)
        )).toList();
        String nextCursor = hasNext ? cursors.encode(FeedCursor.after(sort, categoryId, page.getLast())) : null;
        return new FeedPageResult(items, nextCursor, hasNext);
    }
}
