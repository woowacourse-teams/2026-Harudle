import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getFeedList,
  type FeedListRequest,
  type FeedListResponse,
} from '../../domain/feeds/feedList';
import type { ApiRequest } from '../../shared/api';
import { ERROR_MESSAGES } from '../../shared/errorMessage';

const FEED_SIZE = 20;

const useFeedList = ({
  categoryId,
  sort,
}: Pick<
  FeedListRequest,
  'categoryId' | 'sort'
>): ApiRequest<FeedListResponse> => {
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

  return request;
};

export default useFeedList;
