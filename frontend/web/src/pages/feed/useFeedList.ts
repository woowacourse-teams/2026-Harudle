import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getFeedList,
  type Feed,
  type FeedListRequest,
  type FeedListResponse,
} from '../../domain/feeds/feedList';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';
import type { FeedLikeResponse } from '../../domain/feeds/feedLike';
import { useAnalytics } from '../../posthog/useAnalytics';

interface FeedListState {
  readonly request: ApiRequest<FeedListResponse>;
  readonly loadMoreRequest: ApiRequest<void>;
  readonly loadMore: () => Promise<void>;
  readonly updateLike: (feedId: Feed['id'], like: FeedLikeResponse) => void;
  readonly removeFeedFromList: (feedId: Feed['id']) => void;
}

type FeedListKey =
  `category:${FeedListRequest['categoryId']}&sort:${FeedListRequest['sort']}`;

interface FeedListCache {
  readonly key: FeedListKey;
  readonly data: FeedListResponse;
}

let feedListCache: FeedListCache | null = null;

const FEED_SIZE = 20;

const appendFeedPage = (
  previous: FeedListResponse,
  page: FeedListResponse,
): FeedListResponse => ({
  items: [...previous.items, ...page.items],
  nextCursor: page.nextCursor,
  hasNext: page.hasNext,
});

const updateFeedLike = (
  previous: FeedListResponse,
  feedId: Feed['id'],
  like: FeedLikeResponse,
): FeedListResponse => ({
  ...previous,
  items: previous.items.map((feed): Feed =>
    feed.id === feedId ? { ...feed, ...like } : feed,
  ),
});

const removeFeed = (
  previous: FeedListResponse,
  feedId: Feed['id'],
): FeedListResponse => ({
  ...previous,
  items: previous.items.filter((feed): boolean => feed.id !== feedId),
});

const useFeedList = ({
  categoryId,
  sort,
}: Pick<FeedListRequest, 'categoryId' | 'sort'>): FeedListState => {
  const { track } = useAnalytics();
  const key: FeedListKey = `category:${categoryId}&sort:${sort}`;
  const [request, setRequest] = useState<ApiRequest<FeedListResponse>>(
    (): ApiRequest<FeedListResponse> => {
      const cached = feedListCache;

      return cached === null || cached.key !== key
        ? { status: 'idle' }
        : { status: 'success', data: cached.data };
    },
  );

  const [loadMoreRequest, setLoadMoreRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const abortControllerRef = useRef<AbortController | null>(null);

  const updateFeedStateAndCache = useCallback(
    (data: FeedListResponse): void => {
      feedListCache = { key, data };
      setRequest({ status: 'success', data });
    },
    [key],
  );

  const execute = useCallback(async (): Promise<void> => {
    abortControllerRef.current?.abort();
    const controller = new AbortController();
    abortControllerRef.current = controller;
    const { signal } = controller;

    setRequest({ status: 'loading' });
    setLoadMoreRequest({ status: 'idle' });

    try {
      track('api_requested', {
        feature: 'feed',
        operation: 'feed_list',
        endpoint: '/feeds',
        method: 'GET',
        category_id: categoryId ?? null,
        sort: sort ?? 'LATEST',
        request_reason: 'first_page',
      });
      const data = await getFeedList({
        categoryId,
        sort,
        size: FEED_SIZE,
        signal,
      });
      if (signal.aborted) return;

      updateFeedStateAndCache(data);
    } catch (error: unknown) {
      if (signal.aborted) return;
      setRequest({
        status: 'error',
        error:
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.FEED_LIST_FETCH_FAILED),
      });
    } finally {
      if (abortControllerRef.current === controller) {
        abortControllerRef.current = null;
      }
    }
  }, [categoryId, sort, track, updateFeedStateAndCache]);

  const loadMore = useCallback(async (): Promise<void> => {
    // TODO: 타입 안정성 보강 hasNext, nextCursor
    if (request.status !== 'success' || !request.data.nextCursor) return;

    const controller = new AbortController();
    abortControllerRef.current = controller;
    const { signal } = controller;
    setLoadMoreRequest({ status: 'loading' });

    try {
      track('api_requested', {
        feature: 'feed',
        operation: 'feed_list',
        endpoint: '/feeds',
        method: 'GET',
        category_id: categoryId ?? null,
        sort: sort ?? 'LATEST',
        request_reason: 'load_more',
      });
      const data = await getFeedList({
        categoryId,
        sort,
        cursor: request.data.nextCursor,
        size: FEED_SIZE,
        signal,
      });
      if (signal.aborted) return;

      if (feedListCache?.key !== key) return;

      const nextData = appendFeedPage(feedListCache.data, data);
      updateFeedStateAndCache(nextData);
      setLoadMoreRequest({ status: 'success', data: undefined });
    } catch (error: unknown) {
      if (signal.aborted) return;
      setLoadMoreRequest({
        status: 'error',
        error:
          error instanceof Error
            ? error
            : new Error(ERROR_MESSAGES.FEED_LIST_FETCH_FAILED),
      });
    } finally {
      if (abortControllerRef.current === controller) {
        abortControllerRef.current = null;
      }
    }
  }, [request, categoryId, sort, key, track, updateFeedStateAndCache]);

  useEffect(() => {
    if (feedListCache?.key !== key) {
      feedListCache = null;
      // eslint-disable-next-line react-hooks/set-state-in-effect
      void execute();
    }

    return (): void => abortControllerRef.current?.abort();
  }, [execute, key]);

  const updateLike = useCallback(
    (feedId: Feed['id'], like: FeedLikeResponse): void => {
      if (request.status !== 'success') return;

      const nextData = updateFeedLike(request.data, feedId, like);
      updateFeedStateAndCache(nextData);
    },
    [request, updateFeedStateAndCache],
  );

  const removeFeedFromList = useCallback(
    (feedId: Feed['id']): void => {
      if (request.status !== 'success') return;

      const nextData = removeFeed(request.data, feedId);
      updateFeedStateAndCache(nextData);
    },
    [request, updateFeedStateAndCache],
  );

  return {
    request,
    loadMoreRequest,
    loadMore,
    updateLike,
    removeFeedFromList,
  };
};

export default useFeedList;
