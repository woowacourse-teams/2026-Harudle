import type { FeedListRequest, FeedListResponse } from './feedList';

export type FeedListKey =
  `category:${FeedListRequest['categoryId']}&sort:${FeedListRequest['sort']}`;

interface FeedListCache {
  readonly key: FeedListKey;
  readonly data: FeedListResponse;
}

let feedListCache: FeedListCache | null = null;

export const getFeedListCache = (): FeedListCache | null => feedListCache;

export const setFeedListCache = (cache: FeedListCache | null): void => {
  feedListCache = cache;
};
