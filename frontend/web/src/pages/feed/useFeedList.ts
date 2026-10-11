import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getFeedList,
  type Feed,
  type FeedListRequest,
  type FeedListResponse,
} from '../../domain/feeds/feedList';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

interface FeedListState {
  readonly request: ApiRequest<FeedListResponse>;
  readonly removeFeedFromList: (feedId: Feed['id']) => void;
}

const FEED_SIZE = 20;

const useFeedList = ({
  categoryId,
  sort,
}: Pick<FeedListRequest, 'categoryId' | 'sort'>): FeedListState => {
  const [request, setRequest] = useState<ApiRequest<FeedListResponse>>({
    status: 'idle',
  });
  const abortControllerRef = useRef<AbortController | null>(null);

  const execute = useCallback(async (): Promise<void> => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = new AbortController();
    const { signal } = abortControllerRef.current;

    setRequest({ status: 'loading' });

    try {
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
    }
  }, [categoryId, sort]);

  useEffect((): (() => void) => {
    // 필터가 바뀌면 이전 조회 결과 대신 로딩 상태를 표시한다.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void execute();

    return (): void => abortControllerRef.current?.abort();
  }, [execute]);

  const removeFeedFromList = useCallback((feedId: Feed['id']): void => {
    setRequest((previous): ApiRequest<FeedListResponse> => {
      if (previous.status !== 'success') return previous;
      return {
        ...previous,
        data: {
          ...previous.data,
          items: previous.data.items.filter(
            (feed): boolean => feed.id !== feedId,
          ),
        },
      };
    });
  }, []);

  return { request, removeFeedFromList };
};

export default useFeedList;
