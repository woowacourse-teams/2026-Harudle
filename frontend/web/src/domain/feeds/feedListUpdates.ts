import type { FeedLikeResponse } from './feedLike';
import type { Feed, FeedListResponse } from './feedList';

export const appendFeedPage = (
  previous: FeedListResponse,
  page: FeedListResponse,
): FeedListResponse => ({
  items: [...previous.items, ...page.items],
  nextCursor: page.nextCursor,
  hasNext: page.hasNext,
});

export const updateFeedLike = (
  previous: FeedListResponse,
  feedId: Feed['id'],
  like: FeedLikeResponse,
): FeedListResponse => ({
  ...previous,
  items: previous.items.map((feed): Feed =>
    feed.id === feedId ? { ...feed, ...like } : feed,
  ),
});

export const removeFeed = (
  previous: FeedListResponse,
  feedId: Feed['id'],
): FeedListResponse => ({
  ...previous,
  items: previous.items.filter((feed): boolean => feed.id !== feedId),
});
