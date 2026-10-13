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

const FEED_SIZE = 20;

const useFeedList = ({
  categoryId,
  sort,
}: Pick<FeedListRequest, 'categoryId' | 'sort'>): FeedListState => {
  const { track } = useAnalytics();
  const [request, setRequest] = useState<ApiRequest<FeedListResponse>>({
    status: 'idle',
  });
  const [loadMoreRequest, setLoadMoreRequest] = useState<ApiRequest<void>>({
    status: 'idle',
  });

  const abortControllerRef = useRef<AbortController | null>(null);

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

      setRequest({ status: 'success', data });
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
  }, [categoryId, sort, track]);

  const appendFeedPage = useCallback((page: FeedListResponse): void => {
    setRequest((previous) => {
      if (previous.status !== 'success') return previous;
      return {
        ...previous,
        data: {
          items: [...previous.data.items, ...page.items],
          nextCursor: page.nextCursor,
          hasNext: page.hasNext,
        },
      };
    });
  }, []);

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
      appendFeedPage(data);
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
  }, [request, categoryId, sort, appendFeedPage, track]);

  useEffect((): (() => void) => {
    // 필터가 바뀌면 이전 조회 결과 대신 로딩 상태를 표시한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute();

    return (): void => abortControllerRef.current?.abort();
  }, [execute]);

  const updateLike = useCallback(
    (feedId: Feed['id'], like: FeedLikeResponse): void => {
      setRequest((previous) => {
        if (previous.status !== 'success') return previous;
        return {
          ...previous,
          data: {
            ...previous.data,
            items: previous.data.items.map((feed) =>
              feed.id === feedId ? { ...feed, ...like } : feed,
            ),
          },
        };
      });
    },
    [],
  );

  const removeFeedFromList = useCallback((feedId: Feed['id']): void => {
    setRequest((previous) => {
      if (previous.status !== 'success') return previous;
      return {
        ...previous,
        data: {
          ...previous.data,
          items: previous.data.items.filter((feed) => feed.id !== feedId),
        },
      };
    });
  }, []);

  return {
    request,
    loadMoreRequest,
    loadMore,
    updateLike,
    removeFeedFromList,
  };
};

export default useFeedList;
