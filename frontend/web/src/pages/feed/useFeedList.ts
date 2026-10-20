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

  const setFeedStateAndCache = useCallback(
    (data: FeedListResponse): void => {
      feedListCache = { key, data };
      setRequest({ status: 'success', data });
    },
    [key],
  );

  const updateFeedStateAndCache = useCallback(
    (update: (latest: FeedListResponse) => FeedListResponse): void => {
      if (feedListCache?.key !== key) return;

      // 캐시로부터 항상 최신 값을 읽도록 해서
      // 여러 요청의 응답 순서가 바뀌어도 이전 데이터로 덮어씌워지는 것을 방지함
      const nextData = update(feedListCache.data);
      setFeedStateAndCache(nextData);
    },
    [key, setFeedStateAndCache],
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

      setFeedStateAndCache(data);
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
  }, [categoryId, sort, track, setFeedStateAndCache]);

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

      updateFeedStateAndCache((latest): FeedListResponse =>
        appendFeedPage(latest, data),
      );
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
      updateFeedStateAndCache((latest): FeedListResponse =>
        updateFeedLike(latest, feedId, like),
      );
    },
    [updateFeedStateAndCache],
  );

  const removeFeedFromList = useCallback(
    (feedId: Feed['id']): void => {
      updateFeedStateAndCache((latest): FeedListResponse =>
        removeFeed(latest, feedId),
      );
    },
    [updateFeedStateAndCache],
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
